import sys
import pyttsx3

text = sys.argv[1]
output_file = sys.argv[2]

engine = pyttsx3.init()

engine.setProperty("rate", 150)
engine.setProperty("volume", 1.0)

engine.save_to_file(text, output_file)
engine.runAndWait()

print(output_file)