use strict;
use warnings;
use Socket qw(:DEFAULT IPPROTO_TCP TCP_NODELAY);
use IO::Socket::INET;
use IO::Select;
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
use Errno qw(EAGAIN EWOULDBLOCK EINTR);
use Time::HiRes qw(time);
$SIG{PIPE}='IGNORE';
$| = 1;
alarm 600;
my $host = shift @ARGV;
my $rfb_port = shift @ARGV;
my $mode = shift(@ARGV) // 'target';
my $transport = shift(@ARGV) // 'media';
die 'Invalid transport' unless $transport eq 'media' || $transport eq 'control';
my $media_enabled = $transport eq 'media';
die 'Invalid RFB port' unless defined $rfb_port && $rfb_port =~ /^\d+$/ && $rfb_port>0 && $rfb_port<65536;
die 'Invalid target mode' unless $mode eq 'local' || $mode eq 'target';
# Explicit same-Mac selection: never resolve the public Access hostname as an origin.
# Keep the encoder's interface address distinct from our loopback UDP listeners.
if ($mode eq 'local' && !$media_enabled) {
    $host='127.0.0.1'; # Standard VNC needs neither an encoder interface nor UDP binds.
} elsif ($mode eq 'local') {
    open(my $route_info, '-|', '/sbin/route', '-n', 'get', 'default') or die 'No Mac route';
    my $interface;
    while (<$route_info>) { $interface=$1 if /^\s*interface:\s*([a-zA-Z0-9]+)\s*$/; }
    close $route_info;
    die 'No Mac interface' unless defined $interface;
    open(my $address_info, '-|', '/usr/sbin/ipconfig', 'getifaddr', $interface) or die 'No Mac address';
    $host=<$address_info>;
    close $address_info;
    die 'No Mac IPv4 address' unless defined $host;
    chomp $host;
    die 'Invalid Mac IPv4 address' unless $host =~ /^(?:\d{1,3}\.){3}\d{1,3}$/;
}
my $target = inet_aton($host) or die "Invalid owned target";
die 'Loopback media target would loop relay packets' if $media_enabled && inet_ntoa($target) =~ /^127\./;
my $route = IO::Socket::INET->new(PeerAddr=>$host, PeerPort=>$rfb_port, Proto=>'udp') or die 'No target route';
my $same_host = $route->sockhost eq inet_ntoa($target) || inet_ntoa($target) =~ /^127\./;
close $route;
die 'Mac target is not local' if $mode eq 'local' && !$same_host;
my $listener = IO::Socket::INET->new(LocalAddr=>'127.0.0.1', LocalPort=>0,
    Listen=>8, Proto=>'tcp') or die "Control listener: $!";
my $select = IO::Select->new($listener, \*STDIN);
my (%udp, %peer, %handles, %udp_port);
my %reported_drop;
for my $port ($media_enabled ? (5900, 5901) : ()) {
    socket(my $socket, PF_INET, SOCK_DGRAM, 0) or die "UDP socket: $!";
    bind($socket, sockaddr_in($port, inet_aton($same_host ? '127.0.0.1' : '0.0.0.0'))) or die "UDP port unavailable: $!";
    setsockopt($socket,SOL_SOCKET,SO_RCVBUF,pack('i',4*1024*1024));
    $udp{$port}=$socket;
    $udp_port{fileno($socket)}=$port;
    $select->add($socket);
}
print 'READY ', $listener->sockport, "\n";
my $stdin_buffer='';
my $last_punch=0;
my $writes=IO::Select->new();
my (%tcp_output,%read_eof,%write_eof);
my @media;
my ($media_offset,$media_bytes,$media_drops,$media_forwarded,$control_bytes)=(0,0,0,0,0);
my $last_report=time;
sub nonblocking {
    my ($fh)=@_;
    my $flags=fcntl($fh,F_GETFL,0);
    die "Cannot read descriptor flags: $!" unless defined $flags;
    fcntl($fh,F_SETFL,$flags | O_NONBLOCK) or die "Cannot set nonblocking output: $!";
}
nonblocking(\*STDOUT);
sub retry_write { return $! == EAGAIN || $! == EWOULDBLOCK || $! == EINTR; }
sub enqueue_media {
    my ($port,$packet)=@_;
    my $frame=pack('nn',$port,length($packet)).$packet;
    push @media,$frame; $media_bytes+=length($frame);
    # Never drop a partially written frame: the receiver must retain framing.
    while (@media>512 || $media_bytes>4*1024*1024) {
        my $index=$media_offset>0 ? 1 : 0;
        last if $index>=@media;
        my ($dropped)=splice(@media,$index,1);
        $media_bytes-=length($dropped); $media_drops++;
    }
    $writes->add(\*STDOUT);
}
sub flush_media {
    return unless @media;
    my $count=syswrite(STDOUT,$media[0],length($media[0])-$media_offset,$media_offset);
    return if !defined($count) && retry_write();
    die "Media output failed: $!" unless defined $count && $count>0;
    $media_offset+=$count; $media_bytes-=$count;
    if ($media_offset==length($media[0])) { shift @media; $media_offset=0; $media_forwarded++; }
    $writes->remove(\*STDOUT) unless @media;
}
sub close_pair {
    my ($socket)=@_;
    my $id=fileno($socket);
    return unless defined $id;
    my $other=delete $peer{$id};
    $select->remove($socket);
    delete $handles{$id}; delete $tcp_output{$id}; delete $read_eof{$id}; delete $write_eof{$id};
    $writes->remove($socket);
    if (defined $other && defined fileno($other)) {
        my $other_id=fileno($other);
        $select->remove($other);
        delete $peer{$other_id}; delete $handles{$other_id};
        delete $tcp_output{$other_id}; delete $read_eof{$other_id}; delete $write_eof{$other_id};
        $writes->remove($other);
        close $other;
    }
    close $socket;
}
sub finish_tcp {
    my ($socket)=@_;
    my $id=fileno($socket); return unless defined $id;
    my $other=$peer{$id}; return unless defined $other && defined fileno($other);
    my $other_id=fileno($other);
    if ($read_eof{$other_id} && !length($tcp_output{$id} // '') && !$write_eof{$id}) {
        shutdown($socket,1); $write_eof{$id}=1;
    }
    close_pair($socket) if $read_eof{$id} && $read_eof{$other_id} &&
        !length($tcp_output{$id} // '') && !length($tcp_output{$other_id} // '');
}
while (1) {
    my ($readable,$writable)=IO::Select->select($select,$writes,undef,0.1);
    for my $socket (@{$readable // []}) {
        next unless defined fileno($socket);
        if (fileno($socket)==fileno($listener)) {
            my $incoming=$listener->accept or next;
            # Preserve a distinct loopback client identity while the server's local endpoint remains its LAN address.
            my %options=(PeerAddr=>$host, PeerPort=>$rfb_port, Proto=>'tcp', Timeout=>4);
            $options{LocalAddr}='127.0.0.1' if $same_host;
            my $outgoing=IO::Socket::INET->new(%options);
            unless ($outgoing) { close $incoming; next; }
            for ($incoming,$outgoing) { setsockopt($_,IPPROTO_TCP,TCP_NODELAY,pack('i',1)); nonblocking($_); }
            $peer{fileno($incoming)}=$outgoing;
            $peer{fileno($outgoing)}=$incoming;
            $handles{fileno($incoming)}=$incoming;
            $handles{fileno($outgoing)}=$outgoing;
            $select->add($incoming,$outgoing);
        } elsif (fileno($socket)==fileno(STDIN)) {
            my $count=sysread(STDIN,my $data,65536);
            exit 0 unless defined $count && $count>0;
            alarm 600; # Renew only while the owning SSH client actively sends data.
            $stdin_buffer.=$data;
            while (length($stdin_buffer)>=4) {
                my ($port,$length)=unpack('nn',substr($stdin_buffer,0,4));
                exit 0 if $port==0 && $length==0;
                if ($port==0 && $length==1) {
                    last if length($stdin_buffer)<5;
                    substr($stdin_buffer,0,5,''); # Owner heartbeat for an idle TCP-only desktop.
                    next;
                }
                die 'Unexpected port' unless exists $udp{$port};
                last if length($stdin_buffer)<4+$length;
                substr($stdin_buffer,0,4,'');
                my $packet=substr($stdin_buffer,0,$length,'');
                send($udp{$port},$packet,0,sockaddr_in($port,$target));
            }
        } elsif (exists $udp_port{fileno($socket)}) {
            my $from=recv($socket,my $packet,65535,0);
            next unless defined $from;
            my ($remote_port,$remote_ip)=sockaddr_in($from);
            unless ($remote_ip eq $target) {
                my $port=$udp_port{fileno($socket)};
                my $kind=inet_ntoa($remote_ip) =~ /^127\./ ? 'loopback' : 'other';
                print STDERR "RELAY_DROP $port $kind\n" unless $reported_drop{"$port/$kind"}++;
                next;
            }
            my $port=$udp_port{fileno($socket)};
            enqueue_media($port,$packet);
        } else {
            my $id=fileno($socket);
            my $count=sysread($socket,my $data,65536);
            if (defined $count && $count>0) {
                alarm 600; # Control-only fallback also renews its owned session.
                my $other=$peer{$id};
                my $other_id=fileno($other);
                $tcp_output{$other_id}.=$data;
                $writes->add($other);
                # TCP is lossless: pause reads instead of dropping or accumulating without limit.
                $select->remove($socket) if length($tcp_output{$other_id})>=256*1024;
            } elsif (defined $count) {
                $read_eof{$id}=1; $select->remove($socket);
                finish_tcp($peer{$id}); finish_tcp($socket);
            } elsif (!retry_write()) { close_pair($socket); }
        }
    }
    # Control always progresses independently of a backed-up media stdout channel.
    for my $socket (@{$writable // []}) {
        next unless defined fileno($socket);
        next if fileno($socket)==fileno(STDOUT);
        my $id=fileno($socket);
        next unless exists $peer{$id};
        my $count=syswrite($socket,$tcp_output{$id},(length($tcp_output{$id})<65536 ? length($tcp_output{$id}) : 65536));
        if (defined $count && $count>0) {
            substr($tcp_output{$id},0,$count,''); $control_bytes+=$count;
            $writes->remove($socket) unless length($tcp_output{$id});
            my $other=$peer{$id};
            $select->add($other) if !$read_eof{fileno($other)} && length($tcp_output{$id})<64*1024;
            finish_tcp($socket);
        } elsif (!defined($count) && retry_write()) { next; }
        else { close_pair($socket); }
    }
    flush_media() if @media && grep { defined fileno($_) && fileno($_)==fileno(STDOUT) } @{$writable // []};
    if (time-$last_report>=5) {
        print STDERR "RELAY_STATS queued=$media_bytes dropped=$media_drops forwarded=$media_forwarded control=$control_bytes\n";
        $last_report=time;
    }
    # Responses from the encoder are local; the SSH channel carries the resulting media to Quest.
    if ($media_enabled && time-$last_punch>=0.1) {
        for my $port (5900,5901) { send($udp{$port},"\0",0,sockaddr_in($port,$target)); }
        $last_punch=time;
    }
}
