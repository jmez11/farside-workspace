"""Isolated host-side SFTP fixture. Run with a disposable venv containing Paramiko.
Binds loopback only; expose to Quest with adb reverse tcp:22022 tcp:22022.
All writes are confined to the supplied scratch root. No user SSH accounts involved.
"""
import argparse, errno, os, pathlib, socket, stat, threading, time
import paramiko

parser=argparse.ArgumentParser()
parser.add_argument('--root',required=True)
args=parser.parse_args()
ROOT=pathlib.Path(args.root).resolve()
ROOT.mkdir(parents=True,exist_ok=True)
DATA=ROOT/'remote'; DATA.mkdir(exist_ok=True)
(DATA/'folder').mkdir(exist_ok=True)
(DATA/'hello.txt').write_text('SFTP fixture content\n',encoding='utf-8')
with (DATA/'large.bin').open('wb') as f: f.truncate(500*1024*1024)
KEY=paramiko.RSAKey.generate(2048)
KEY.write_private_key_file(str(ROOT/'fixture-key.pem'))

class Server(paramiko.ServerInterface):
 def check_auth_password(self,user,password):
  return paramiko.AUTH_SUCCESSFUL if user=='fixture' and password=='fixture' else paramiko.AUTH_FAILED
 def check_auth_publickey(self,user,key):
  return paramiko.AUTH_SUCCESSFUL if user=='fixture' and key==KEY else paramiko.AUTH_FAILED
 def get_allowed_auths(self,user): return 'publickey,password'
 def check_channel_request(self,kind,chanid): return paramiko.OPEN_SUCCEEDED if kind=='session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

class Files(paramiko.SFTPServerInterface):
 def path(self,p):
  q=(DATA/p.lstrip('/')).resolve()
  if q!=DATA and DATA not in q.parents: raise OSError(errno.EACCES,'outside fixture')
  if q.name=='denied': raise OSError(errno.EACCES,'fixture denied')
  return q
 def canonicalize(self,p): return '/' if p=='.' else '/'+p.strip('/')
 def list_folder(self,p):
  try:
   result=[]
   for q in self.path(p).iterdir():
    a=paramiko.SFTPAttributes.from_stat(q.stat());a.filename=q.name;result.append(a)
   if self.path(p)==DATA:
    for name in ['link','broken','denied']:
     a=paramiko.SFTPAttributes();a.filename=name;a.st_mode=(stat.S_IFREG if name=='denied' else stat.S_IFLNK)|0o444;a.st_size=0;a.st_mtime=0;a.st_atime=0;result.append(a)
   return result
  except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
 def stat(self,p):
  if p=='/link':p='/hello.txt'
  if p=='/broken':return paramiko.SFTP_NO_SUCH_FILE
  try:return paramiko.SFTPAttributes.from_stat(self.path(p).stat())
  except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
 def lstat(self,p):
  if p in ['/link','/broken']:
   a=paramiko.SFTPAttributes();a.st_mode=stat.S_IFLNK|0o777;a.st_size=0;return a
  return self.stat(p)
 def readlink(self,p):return '/hello.txt' if p=='/link' else '/missing'
 def open(self,p,flags,attr):
  try:
   fd=os.open(self.path(p),flags|os.O_BINARY,0o600)
   mode='r+b' if flags&os.O_RDWR else 'wb' if flags&os.O_WRONLY else 'rb'
   f=os.fdopen(fd,mode);h=paramiko.SFTPHandle(flags)
   if flags&os.O_WRONLY or flags&os.O_RDWR:h.writefile=f
   if not flags&os.O_WRONLY:h.readfile=f
   h.stat=lambda:paramiko.SFTPAttributes.from_stat(os.fstat(f.fileno()))
   return h
  except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
 def remove(self,p):return self.change(lambda:self.path(p).unlink())
 def rename(self,p,q):
  if self.path(q).exists():return paramiko.SFTP_FAILURE
  return self.change(lambda:self.path(p).rename(self.path(q)))
 def mkdir(self,p,attr):return self.change(lambda:self.path(p).mkdir())
 def rmdir(self,p):return self.change(lambda:self.path(p).rmdir())
 def chattr(self,p,attr):return paramiko.SFTP_OK
 def change(self,fn):
  try:fn();return paramiko.SFTP_OK
  except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)

def serve(port,sftp):
 sock=socket.socket();sock.bind(('127.0.0.1',port));sock.listen()
 def client(conn):
  transport=paramiko.Transport(conn)
  transport.add_server_key(KEY)
  if sftp:transport.set_subsystem_handler('sftp',paramiko.SFTPServer,Files)
  try:
   transport.start_server(server=Server())
   while transport.is_active():time.sleep(.1)
  finally:transport.close()
 while True:
  conn,_=sock.accept();threading.Thread(target=client,args=(conn,),daemon=True).start()
for port,sftp in [(22022,True),(22023,False)]:threading.Thread(target=serve,args=(port,sftp),daemon=True).start()
print('Fixture ready',flush=True)
while True:time.sleep(1)
