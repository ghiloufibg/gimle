# VHS tape template for Gimlé CLI screencasts — render with: vhs demo.tape
# Record against a real running cluster (see gimle-console/LOCAL_DEV.md); use Hide/Show to keep
# the boring bring-up off-camera. Every command must be a real gimle-cli invocation.

Output demo.webm
Output demo.gif

Set FontFamily "JetBrains Mono"
Set FontSize 18
Set Width 1200
Set Height 640
Set Padding 24
Set TypingSpeed 75ms
# Docusaurus dark surface, so the frame sits naturally on the docs page in dark mode.
Set Theme { "name": "gimle", "background": "#1b1b1d", "foreground": "#e3e3e3", "cursor": "#6ea8dc", "black": "#1b1b1d", "blue": "#6ea8dc", "cyan": "#8dbbe4", "green": "#7cbf8e", "magenta": "#b48ead", "red": "#d97b7b", "white": "#e3e3e3", "yellow": "#e0c07f" }

Hide
Type "cd /path/to/your/workspace"
Enter
Show

Type "gimle deploy greeter-provider-1.0.0.jar"
Enter
# Prefer waiting on real output over guessing sleeps:
Wait /ACTIVE/
Sleep 2s

Type "gimle instances"
Enter
Sleep 3s

Screenshot demo-poster.png
