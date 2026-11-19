# Privacy

Le Dog uploads nothing unless you run `/ledog share on`. You can stop at any time with `/ledog share off`.

When sharing is on, the mod sends, about once a minute, the dog-training feedback you gave since the last upload:

- a random install id, created on your computer (not linked to your Minecraft account)
- the mod version and dog-brain version
- per feedback event: the kind of reward (for example `bone` or `no`), its value, the dog's hunger, a one-way hashed
  dog id, the trick word if you used one (a single lowercase word), and for the moments the reward was credited to:
  the dog's action and the 28 numbers describing what it perceived (directions and distances, speed, posture)

It never sends your username, chat messages, world contents, coordinates of your world, or anything else.

`/ledog update` downloads model files from this repository's GitHub releases and checks their SHA-256 checksums.

The collected data is used only to train Le Dog's dogs. To ask for deletion of the data from your install id, open
an issue with the id shown in `~/.ledog/share.json`.
