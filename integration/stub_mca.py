import http.server, json, os

SCENARIO_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scenario.json")

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            with open(SCENARIO_FILE) as f:
                data = json.load(f)
        except Exception:
            data = {"assigned": False}
        body = json.dumps({"success": True, "message": "ok", "data": data}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, fmt, *args):
        print("[stub-mca]", self.address_string(), self.path, flush=True)

http.server.HTTPServer(("127.0.0.1", 18080), Handler).serve_forever()
