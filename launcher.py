"""Точка входа для сборки в .exe (PyInstaller).

Запускает Flask-сервер локально и открывает браузер на его адресе.
Обычная разработка ведётся через app.py — этот файл нужен только для
автономного desktop-запуска.
"""
import socket
import threading
import time
import webbrowser

from app import app

HOST = "127.0.0.1"
PORT = 5000


def _port_is_open(host, port, timeout=0.3):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        return sock.connect_ex((host, port)) == 0


def _open_browser_when_ready():
    for _ in range(50):
        if _port_is_open(HOST, PORT):
            webbrowser.open(f"http://{HOST}:{PORT}")
            return
        time.sleep(0.2)


if __name__ == "__main__":
    threading.Thread(target=_open_browser_when_ready, daemon=True).start()
    # debug=False и use_reloader=False обязательны: в собранном .exe
    # перезапуск процесса реloader'ом не работает и приведёт к падению.
    app.run(host=HOST, port=PORT, debug=False, use_reloader=False)
