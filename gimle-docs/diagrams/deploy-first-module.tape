# Real gimle-cli commands against a real local cluster (a genuine gimle:store + gimle:controlplane
# + gimle:agent trio, see gimle-console/LOCAL_DEV.md) -- deploying the actual greeter-provider/
# greeter-consumer pair from gimle-examples/. Render: vhs deploy-first-module.tape
Output deploy-first-module.webm
Output deploy-first-module.gif

Set FontFamily "JetBrains Mono"
Set FontSize 18
Set Width 1200
Set Height 640
Set Padding 24
Set TypingSpeed 75ms
# Docusaurus dark surface, so the frame sits naturally on the docs page in dark mode.
Set Theme { "name": "gimle", "background": "#1b1b1d", "foreground": "#e3e3e3", "cursor": "#6ea8dc", "black": "#1b1b1d", "blue": "#6ea8dc", "cyan": "#8dbbe4", "green": "#7cbf8e", "magenta": "#b48ead", "red": "#d97b7b", "white": "#e3e3e3", "yellow": "#e0c07f" }

Hide
# Adjust the cd target to your own checkout; PATH must resolve a "gimle" launcher (see
# gimle-dist/src/main/dist/bin/gimle, or gimle-console/LOCAL_DEV.md for a from-source classpath).
Type "cd /path/to/your/gimle/checkout && clear"
Enter
Show

Type "gimle get nodes"
Enter
Sleep 2s

Type "gimle apply -f gimle-examples/greeter-provider/deployment.yaml"
Enter
Sleep 6s

Type "gimle apply -f gimle-examples/greeter-consumer/deployment.yaml"
Enter
Sleep 8s

Type "gimle get deployments"
Enter
Sleep 2s

Type "gimle logs instance/greeter-consumer-deployment/0"
Enter
Sleep 3s
