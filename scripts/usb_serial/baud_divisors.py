#!/usr/bin/env python3
"""Independently re-derives the CH34x and FTDI baud-divisor known-answer vectors used by
UsbSerialTest.kt (domain/src/test/kotlin/net/jamesjennison/klippercompanion/UsbSerialTest.kt).

This is a from-scratch re-implementation of the *equations* described in the Linux kernel's
usb-serial drivers (GPL-2.0, commit 551c722f40809618230001baccf219193e22fc5a) - see
/mnt/faststorage/Test Slicer/linux-usb-serial/ch341.c and ftdi_sio.c, and
docs/upstream/PROVENANCE.md P-0038. No kernel source or comments are copied here; only the
documented maths (clock rate, prescaler/divisor search, fractional-divisor encoding) is
reproduced, in Python, as an independent check against the Kotlin implementation in
UsbSerial.kt. Written for this change; not derived from any single kernel source file.

Run: python3 scripts/usb_serial/baud_divisors.py
"""
from __future__ import annotations


def ch34x_divisor(baud: int) -> int:
    """48 MHz reference; baudrate = 48000000 / (2^(12-3*ps-fact) * div), ps in 0..3, fact in {0,1}."""
    clk_rate = 48_000_000

    def clk_div(ps: int, fact: int) -> int:
        return 1 << (12 - 3 * ps - fact)

    def min_rate(ps: int) -> int:
        return clk_rate // (clk_div(ps, 1) * 512)

    baud = max(46, min(2_000_000, baud))
    ps = 3
    while ps >= 0 and baud <= min_rate(ps):
        ps -= 1
    assert ps >= 0, "baud out of range"

    fact = 1
    clk_div_value = clk_div(ps, fact)
    div = clk_rate // (clk_div_value * baud)
    if div < 9 or div > 255:
        div //= 2
        clk_div_value *= 2
        fact = 0
    assert div >= 2

    rate_at_div = 16 * clk_rate // (clk_div_value * div) - 16 * baud
    rate_at_div_plus1 = 16 * baud - 16 * clk_rate // (clk_div_value * (div + 1))
    if rate_at_div >= rate_at_div_plus1:
        div += 1
    if fact == 1 and div % 2 == 0:
        div //= 2
        fact = 0

    return ((0x100 - div) & 0xFF) << 8 | (fact << 2) | ps


def ftdi_bm_divisor(baud: int) -> int:
    """FT232BM/FT232R-family divisor from a 48 MHz reference, with an 8-step fractional part."""
    divfrac = [0, 3, 2, 4, 1, 5, 6, 7]
    divisor3 = round(48_000_000 / (2 * baud))
    divisor = divisor3 >> 3
    divisor |= divfrac[divisor3 & 0x7] << 14
    if divisor == 1:
        divisor = 0
    elif divisor == 0x4001:
        divisor = 1
    return divisor & 0xFFFF


if __name__ == "__main__":
    for baud in (57_600, 115_200, 250_000):
        ch = ch34x_divisor(baud)
        ft = ftdi_bm_divisor(baud)
        print(f"{baud:>7} bps -> CH34x divisor=0x{ch:04X} ({ch})  FTDI divisor=0x{ft:04X} ({ft})")
