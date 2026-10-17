"""Bounded typing plans: split long text into requests that each finish inside a timeout.

The bridge's node text input (ACTION_SET_TEXT) accepts a bounded piece per call, so a
long text is sent as ordered pieces. Splitting on grapheme-safe boundaries keeps
combining marks, emoji sequences and flags intact across pieces.
"""
import unicodedata

REGIONAL_INDICATORS = range(0x1F1E6, 0x1F200)
SKIN_TONES = range(0x1F3FB, 0x1F400)
VARIATION_SELECTORS = (range(0xFE00, 0xFE10), range(0xE0100, 0xE01F0))
EMOJI_TAGS = range(0xE0020, 0xE0080)
ZERO_WIDTH_JOINER = "‍"


def utf16_length(text):
    """Return the Kotlin/Java ``String.length`` of ``text``.

    Android's ``ACTION_SET_TEXT`` limit is expressed in UTF-16 code units while
    Python's ``len`` counts Unicode code points.  Counting the encoded units keeps
    astral characters (emoji, many CJK extensions, and similar code points) aligned
    with the device-side contract.
    """
    return len(text.encode("utf-16-le")) // 2


def _extends_previous(character):
    """True when this code point belongs to the grapheme started before it."""
    code = ord(character)
    return (character == ZERO_WIDTH_JOINER or unicodedata.combining(character) != 0
            or unicodedata.category(character) in ("Mn", "Me", "Mc")
            or code in SKIN_TONES or code in EMOJI_TAGS
            or any(code in block for block in VARIATION_SELECTORS))


def safe_boundary(text, index):
    """Whether text may be cut before text[index] without separating one grapheme."""
    if index <= 0 or index >= len(text):
        return True
    previous, following = text[index - 1], text[index]
    if previous == ZERO_WIDTH_JOINER or _extends_previous(following):
        return False
    if previous == "\r" and following == "\n":
        return False
    if ord(previous) in REGIONAL_INDICATORS and ord(following) in REGIONAL_INDICATORS:
        # Flags are pairs: cutting is safe only after an even run of indicators.
        run = 0
        cursor = index - 1
        while cursor >= 0 and ord(text[cursor]) in REGIONAL_INDICATORS:
            run += 1
            cursor -= 1
        return run % 2 == 0
    return True


def split_text(text, size):
    """Split into ordered pieces of at most `size` UTF-16 units.

    A piece is only cut at a grapheme-safe boundary.  A single grapheme that is
    larger than the requested limit is rejected instead of sending an over-limit
    ``ACTION_SET_TEXT`` request.
    """
    if size < 1:
        raise ValueError("size must be positive")
    pieces, start = [], 0
    while start < len(text):
        end, units, last_safe = start, 0, start
        while end < len(text):
            next_units = 2 if ord(text[end]) > 0xFFFF else 1
            if units + next_units > size:
                break
            end += 1
            units += next_units
            if safe_boundary(text, end):
                last_safe = end
        if end == len(text):
            pieces.append(text[start:])
            break
        if last_safe == start:
            # The current grapheme does not fit.  Never split it into a lone
            # surrogate or an incomplete ZWJ/combining sequence.
            probe = max(end, start + 1)
            while probe < len(text) and not safe_boundary(text, probe):
                probe += 1
            if probe <= start or utf16_length(text[start:probe]) > size:
                raise ValueError("a grapheme exceeds the UTF-16 piece limit")
            last_safe = probe
        pieces.append(text[start:last_safe])
        start = last_safe
    return pieces


def request_timeout(characters, frequency, floor=15.0):
    """Typing takes characters / frequency seconds; allow double that plus overhead."""
    return max(floor, characters / frequency * 2 + 10)
