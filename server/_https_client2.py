import ssl, uuid, io, http.client
ctx = ssl._create_unverified_context()
data = b'\x00\x01\x02not-a-real-container'*20
b='----t'+uuid.uuid4().hex
body=io.BytesIO()
body.write(('--'+b+'\r\n').encode())
body.write(b'Content-Disposition: form-data; name=\"audio\"; filename=\"broken.webm\"\r\n\r\n')
body.write(data); body.write(b'\r\n'); body.write(('--'+b+'--\r\n').encode())
raw=body.getvalue()
c=http.client.HTTPSConnection('127.0.0.1',8444,timeout=120,context=ctx)
c.request('POST','/upload',body=raw,headers={'Content-Type':'multipart/form-data; boundary='+b,'Content-Length':str(len(raw))})
r=c.getresponse(); out=r.read().decode('utf-8')
print('STATUS',r.status); print('RESP',out)
