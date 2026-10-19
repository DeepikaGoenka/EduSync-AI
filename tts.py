"""
Usage: python tts.py <text_file.txt> <output_base>

Reads UTF-8 text from the text file and writes the speech to
  <output_base>.mp3   (edge-tts: natural voice, needs internet)
or, if edge-tts is not installed / offline,
  <output_base>.wav   (pyttsx3: robotic offline voice)

The Java side looks for either file, so it does not matter which one was made.

Install once:   pip install edge-tts
Change voice:   set EDGE_VOICE=en-IN-PrabhatNeural   (Windows)  e.g.
                en-IN-NeerjaNeural (default), en-IN-PrabhatNeural, en-US-AriaNeural, en-GB-SoniaNeural
Change speed:   set EDGE_RATE=-5%   (slower)  or  +10%  (faster)
"""
import asyncio
import os
import sys

VOICE = os.environ.get("EDGE_VOICE", "en-IN-NeerjaNeural")
RATE = os.environ.get("EDGE_RATE", "+0%")


def remove(path):
    try:
        if os.path.exists(path):
            os.remove(path)
    except OSError:
        pass


def with_edge(text, base):
    import edge_tts  # imported here so a missing package falls through to pyttsx3

    out = base + ".mp3"

    async def go():
        await edge_tts.Communicate(text, VOICE, rate=RATE).save(out)

    asyncio.run(go())
    if not os.path.exists(out) or os.path.getsize(out) == 0:
        raise RuntimeError("edge-tts produced no audio")
    return out


def with_pyttsx3(text, base):
    import pyttsx3

    out = base + ".wav"
    engine = pyttsx3.init()
    engine.setProperty("rate", 160)
    engine.setProperty("volume", 1.0)
    engine.save_to_file(text, out)
    engine.runAndWait()
    if not os.path.exists(out) or os.path.getsize(out) == 0:
        raise RuntimeError("pyttsx3 produced no audio")
    return out


def main():
    if len(sys.argv) < 3:
        print("usage: python tts.py <text_file> <output_base>", file=sys.stderr)
        return 2

    with open(sys.argv[1], encoding="utf-8") as f:
        text = f.read().strip()
    base = sys.argv[2]
    if not text:
        print("empty text", file=sys.stderr)
        return 2

    remove(base + ".mp3")
    remove(base + ".wav")

    for attempt in (1, 2):
        try:
            out = with_edge(text, base)
            print(out, flush=True)
            return 0
        except Exception as e:
            print("edge-tts attempt %d failed: %s" % (attempt, e), file=sys.stderr)
            remove(base + ".mp3")

    try:
        out = with_pyttsx3(text, base)
        print(out, flush=True)
        return 0
    except Exception as e:
        print("pyttsx3 failed: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
