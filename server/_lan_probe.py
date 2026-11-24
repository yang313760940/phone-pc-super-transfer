import ssl, urllib.request
ctx = ssl._create_unverified_context()
try:
    r = urllib.request.urlopen('https://192.168.31.61:8443/healthz', context=ctx, timeout=8)
    print('LAN_IP_OK', r.status, r.read().decode())
except Exception as e:
    print('LAN_IP_FAIL', type(e).__name__, e)
