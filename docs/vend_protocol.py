"""
Протокол платы привода пружин (восстановлен из APK com.dingyou.qipai.org v1.0.2.5,
класс SerialPort в Assembly-CSharp.dll + Lua-скрипт LuckyBagStartManage.lua).

Порт:   /dev/ttyS<APort> (по умолчанию /dev/ttyS1), 115200 бод.
        Если в настройках MainSerialPort == 0, то /dev/ttyS0.
Кадр:   8 байт в обе стороны
        [0]=0x58 [1]=0x50 [2]=cmd [3]=a' [4]=b' [5]=rnd [6]=chk [7]=0x4C
        rnd — случайный байт. Если (rnd & 0x20) == 0:  a' = ~a,  b' = ~b
                              иначе:                   a' = a^rnd, b' = b^rnd
        chk = XOR байтов [0..5]

Команды Android -> плата:
        0x00, 0xE0, 0xE0   рукопожатие, шлётся один раз после открытия порта
        0x11, row, col     прокрутить мотор; row/col = 1..10
        0xAA, 0, 0         сброс моторов
Ответы плата -> Android:
        0x11, row, col     мотор отработал штатно
        0x10, row, col     ошибка мотора
        0x50, 0, 0         нажата сервисная кнопка (вход в админку)
        0xAA, 0, 0         подтверждение сброса моторов
Слот -> row/col:  row = (slot-1)//10 + 1,  col = (slot-1)%10 + 1   (slot = 1..100)
Таймаут ответа в родном приложении: настройка DriveTime, по умолчанию 60 с.
"""
import os

HEAD = (0x58, 0x50)
TAIL = 0x4C
CMD_HANDSHAKE, CMD_SPIN, CMD_SPIN_ERR, CMD_SET_BTN, CMD_RESET = 0x00, 0x11, 0x10, 0x50, 0xAA


def encode(cmd: int, a: int, b: int, rnd: int | None = None) -> bytes:
    if rnd is None:
        rnd = os.urandom(1)[0]
    if rnd & 0x20 == 0:
        a2, b2 = ~a & 0xFF, ~b & 0xFF
    else:
        a2, b2 = a ^ rnd, b ^ rnd
    body = [HEAD[0], HEAD[1], cmd, a2, b2, rnd]
    chk = 0
    for x in body:
        chk ^= x
    return bytes(body + [chk, TAIL])


def decode(frame: bytes) -> tuple[int, int, int]:
    """Возвращает (cmd, a, b). Бросает ValueError при неверной длине или контрольной сумме."""
    if len(frame) != 8:
        raise ValueError("frame must be 8 bytes")
    chk = 0
    for x in frame[:6]:
        chk ^= x
    if chk != frame[6]:
        raise ValueError("bad checksum")
    cmd, a, b, rnd = frame[2], frame[3], frame[4], frame[5]
    if rnd & 0x20 == 0:
        a, b = ~a & 0xFF, ~b & 0xFF
    else:
        a, b = a ^ rnd, b ^ rnd
    return cmd, a, b


def slot_to_rc(slot: int) -> tuple[int, int]:
    if not 1 <= slot <= 100:
        raise ValueError("slot 1..100")
    return (slot - 1) // 10 + 1, (slot - 1) % 10 + 1


def rc_to_slot(row: int, col: int) -> int:
    return (row - 1) * 10 + col


def handshake(rnd=None) -> bytes:
    return encode(CMD_HANDSHAKE, 0xE0, 0xE0, rnd)


def spin(slot: int, rnd=None) -> bytes:
    row, col = slot_to_rc(slot)
    return encode(CMD_SPIN, row, col, rnd)


def parse_reply(frame: bytes) -> dict:
    cmd, a, b = decode(frame)
    if cmd == CMD_SPIN:
        return {"event": "spin_ok", "slot": rc_to_slot(a, b)}
    if cmd == CMD_SPIN_ERR:
        return {"event": "spin_error", "slot": rc_to_slot(a, b)}
    if cmd == CMD_SET_BTN and a == 0 and b == 0:
        return {"event": "service_button"}
    if cmd == CMD_RESET and a == 0 and b == 0:
        return {"event": "motors_reset"}
    return {"event": "unknown", "cmd": cmd, "a": a, "b": b}


if __name__ == "__main__":
    # самопроверка: кодирование/декодирование для всех rnd и всех слотов
    for rnd in range(256):
        for slot in range(1, 101):
            f = spin(slot, rnd)
            assert parse_reply(f) == {"event": "spin_ok", "slot": slot}, (rnd, slot)
        assert decode(handshake(rnd)) == (0x00, 0xE0, 0xE0)
    print("self-test OK")
    hx = lambda f: " ".join(f"{x:02X}" for x in f)
    px = lambda f: "".join(f"\\x{x:02x}" for x in f)
    print("handshake (rnd=00):", hx(handshake(0)), "  printf:", px(handshake(0)))
    for s in range(1, 11):
        print(f"spin slot {s:>2} (rnd=00):", hx(spin(s, 0)), "  printf:", px(spin(s, 0)))
