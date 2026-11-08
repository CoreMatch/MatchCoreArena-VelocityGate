"""Minimal Minecraft 1.8 (protocol 47) login backend for proxy integration tests.

Accepts handshake + login start, answers login success with the offline-mode
UUID, then keeps the connection open. Logs every accepted player.
Usage: python3 fake_backend.py <port> <label>
"""
import socket
import struct
import sys
import hashlib
import threading


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("eof")
        buf += chunk
    return buf


def recv_varint(sock):
    val = 0
    shift = 0
    while True:
        b = recv_exact(sock, 1)[0]
        val |= (b & 0x7F) << shift
        if not (b & 0x80):
            return val
        shift += 7
        if shift > 35:
            raise ValueError("varint too long")


def varint_bytes(val):
    out = b""
    while True:
        b = val & 0x7F
        val >>= 7
        if val:
            out += bytes([b | 0x80])
        else:
            out += bytes([b])
            return out


def pack_string(s):
    b = s.encode("utf-8")
    return varint_bytes(len(b)) + b


def send_packet(sock, pid, payload=b""):
    body = bytes([pid]) + payload
    sock.sendall(varint_bytes(len(body)) + body)


def offline_uuid(name):
    md5 = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    md5[6] = (md5[6] & 0x0F) | 0x30
    md5[8] = (md5[8] & 0x3F) | 0x80
    h = md5.hex()
    return "%s-%s-%s-%s-%s" % (h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])


def read_string(payload, offset):
    length = 0
    shift = 0
    while True:
        b = payload[offset]
        offset += 1
        length |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return payload[offset:offset + length].decode("utf-8"), offset + length


def handle(conn, addr, label):
    try:
        # handshake packet: read and ignore content
        ln = recv_varint(conn)
        recv_exact(conn, ln)
        # login start packet: pid 0x00 + username string
        ln = recv_varint(conn)
        payload = recv_exact(conn, ln)
        if payload[0] != 0x00:
            print("[%s] unexpected packet id %d" % (label, payload[0]), flush=True)
            return
        name, _ = read_string(payload, 1)
        uuid = offline_uuid(name)
        send_packet(conn, 0x02, pack_string(uuid) + pack_string(name))
        # JoinGame (protocol 47, play state 0x01): Velocity only completes the
        # server transition (and fires ServerPostConnectEvent) after it.
        join_game = struct.pack(">i", 1) + bytes([0, 0, 0, 20]) \
            + pack_string("default") + bytes([0])
        send_packet(conn, 0x01, join_game)
        print("[%s] LOGIN OK player=%s uuid=%s from=%s" % (label, name, uuid, addr),
              flush=True)
        # keep the connection alive until the peer closes it
        while True:
            data = conn.recv(4096)
            if not data:
                break
    except Exception as e:
        print("[%s] connection error: %r" % (label, e), flush=True)
    finally:
        conn.close()


def main():
    port = int(sys.argv[1])
    label = sys.argv[2]
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(8)
    print("[%s] listening on 127.0.0.1:%d" % (label, port), flush=True)
    while True:
        conn, addr = server.accept()
        t = threading.Thread(target=handle, args=(conn, addr, label), daemon=True)
        t.start()


if __name__ == "__main__":
    main()
