import socket, struct, time, re

def rcon(sock, reqid, rtype, payload):
    data = struct.pack('<ii', reqid, rtype) + payload.encode('ascii') + b'\x00\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)
    time.sleep(0.5)
    resp = sock.recv(4096)
    length = struct.unpack('<i', resp[:4])[0]
    body = resp[12:4+length-2].decode('ascii', errors='replace')
    return body

try:
    s = socket.socket()
    s.settimeout(5)
    s.connect(('127.0.0.1', 25575))
    print('Connected to RCON')
    print('auth:', rcon(s, 1, 3, 'test123'))
    players = rcon(s, 2, 2, 'list')
    print('players:', players)
    names = re.findall(r':\s*(.+)', players)
    if names:
        for name in names[0].split(', '):
            name = name.strip()
            if name:
                print('op:', rcon(s, 3, 2, 'op ' + name))
                print('gm:', rcon(s, 4, 2, 'gamemode creative ' + name))
    time.sleep(1)
    print('play:', rcon(s, 5, 2, 'cinematic play TwoTime'))
    s.close()
    print('Done')
except Exception as e:
    print('Error:', e)
