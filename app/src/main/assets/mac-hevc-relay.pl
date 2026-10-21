use strict;
use warnings;
use Socket;
use IO::Socket::INET;
use IO::Select;
use Time::HiRes qw(time);
$SIG{PIPE}='IGNORE';
$| = 1;
alarm 600;
my $host = shift @ARGV;
my $rfb_port = shift @ARGV;
die 'Invalid RFB port' unless defined $rfb_port && $rfb_port =~ /^\d+$/ && $rfb_port>0 && $rfb_port<65536;
my $target = inet_aton($host) or die "Invalid owned target";
my $listener = IO::Socket::INET->new(LocalAddr=>'127.0.0.1', LocalPort=>0,
    Listen=>8, Proto=>'tcp') or die "Control listener: $!";
my $select = IO::Select->new($listener, \*STDIN);
my (%udp, %peer, %handles, %udp_port);
for my $port (5900, 5901) {
    socket(my $socket, PF_INET, SOCK_DGRAM, 0) or die "UDP socket: $!";
    bind($socket, sockaddr_in($port, inet_aton('127.0.0.1'))) or die "Loopback UDP port unavailable: $!";
    setsockopt($socket,SOL_SOCKET,SO_RCVBUF,pack('i',4*1024*1024));
    $udp{$port}=$socket;
    $udp_port{fileno($socket)}=$port;
    $select->add($socket);
}
print 'READY ', $listener->sockport, "\n";
my $stdin_buffer='';
my $last_punch=0;
sub write_all {
    my ($fh,$data)=@_;
    while (length $data) {
        my $count=syswrite($fh,$data);
        die "Write failed: $!" unless defined $count && $count>0;
        substr($data,0,$count,'');
    }
}
sub close_pair {
    my ($socket)=@_;
    my $id=fileno($socket);
    return unless defined $id;
    my $other=delete $peer{$id};
    $select->remove($socket);
    delete $handles{$id};
    if (defined $other && defined fileno($other)) {
        my $other_id=fileno($other);
        $select->remove($other);
        delete $peer{$other_id}; delete $handles{$other_id};
        close $other;
    }
    close $socket;
}
while (1) {
    for my $socket ($select->can_read(0.1)) {
        next unless defined fileno($socket);
        if (fileno($socket)==fileno($listener)) {
            my $incoming=$listener->accept or next;
            # Preserve a distinct loopback client identity while the server's local endpoint remains its LAN address.
            my $outgoing=IO::Socket::INET->new(PeerAddr=>$host, PeerPort=>$rfb_port,
                LocalAddr=>'127.0.0.1', Proto=>'tcp', Timeout=>5);
            unless ($outgoing) { close $incoming; next; }
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
            next unless $remote_ip eq $target;
            my $port=$udp_port{fileno($socket)};
            write_all(\*STDOUT,pack('nn',$port,length($packet)).$packet);
        } else {
            my $id=fileno($socket);
            my $count=sysread($socket,my $data,65536);
            if (defined $count && $count>0) {
                my $ok=eval { write_all($peer{$id},$data); 1; };
                close_pair($socket) unless $ok;
            } else {
                close_pair($socket);
            }
        }
    }
    # Responses from the encoder are local; the SSH channel carries the resulting media to Quest.
    if (time-$last_punch>=0.1) {
        for my $port (5900,5901) { send($udp{$port},"\0",0,sockaddr_in($port,$target)); }
        $last_punch=time;
    }
}
