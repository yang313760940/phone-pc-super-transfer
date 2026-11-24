import ssl, uuid, io, http.client
ctx = ssl._create_unverified_context()
data = open('samples/zh_test.wav','rb').read()
b='----t'+uuid.uuid4().hex
body=io.BytesIO()
body.write(('--'+b+'\r\n').encode())
body.write(b'Content-Disposition: form-data; name=\"audio\"; filename=\"zh_test.wav\"\r\n\r\n')
body.write(data); body.write(b'\r\n'); body.write(('--'+b+'--\r\n').encode())
raw=body.getvalue()
c=http.client.HTTPSConnection('127.0.0.1',8443,timeout=120,context=ctx)
c.request('POST','/upload',body=raw,headers={'Content-Type':'multipart/form-data; boundary='+b,'Content-Length':str(len(raw))})
r=c.getresponse(); out=r.read().decode('utf-8')
print('STATUS',r.status); print('RESP',out)
