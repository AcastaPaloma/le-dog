# Le Dog

Minecraft wolves that act like real dogs, and dogs you can train with treats.

Le Dog is a Fabric mod for Minecraft 26.3. Its dogs are driven by a small neural network (about 1 ms per decision)
distilled from a large language model that plans dog behaviour from what the dog perceives, then tuned until its
movement statistics match real dogs filmed with head and back cameras. Each dog has a temperament (bold, shy, lazy,
playful, curious, social) that visibly changes what it does.

Trick dogs also learn: say a command in chat, reward what they do with steak, bones or praise, and they get better.
If you opt in, your anonymous training feedback helps train the next version of the dogs for every player.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.3.
2. Download `le-dog-<version>.jar` from the [latest release](https://github.com/AcastaPaloma/le-dog/releases/latest) into your `mods` folder.
3. In a world, run `/ledog update` once to download the dog brains (a few MB, checksum-verified) into `~/.ledog`.

## Commands

| Command | What it does |
|---|---|
| `/ledog student [bold\|shy\|lazy\|playful\|curious\|social]` | spawn a dog with that temperament |
| `/ledog trick [preset]` | spawn a trick dog |
| `/ledog debug` | show each dog's current plan and what it is looking at |
| `/ledog update` | download the newest dog brains |
| `/ledog share on\|off` | share your dog-training feedback anonymously (off by default) |

## Training a trick dog

Type a command in chat within 32 blocks: `sit`, `come`, `jump`, `stay`. A dog that gets it right earns hearts.
Reward or correct what it just did:

| Reward | How | Strength |
|---|---|---|
| golden carrot | right-click the dog | +2.0 |
| steak (cooked or raw beef) | right-click | +1.0, more when it is hungry |
| bone | right-click | +0.7 (it is a treat, not taming) |
| praise | type `good`, `good dog`, `yes`, `nice` | +0.4 |
| pet | sneak + right-click with an empty hand | +0.3 |
| no | type `no` or `bad` | -0.5 |
| clicker | right-click the air holding a stick | marks the exact moment the next reward is for |

New words work too: type any single word (`paw`, `spin`, `down`); the dog tries a move each second, and you reward
the one you want. After a few rewards the word sticks. Each dog remembers its training across sessions.

## Community training

With `/ledog share on`, your feedback events are uploaded anonymously to the Le Dog collector (see
[PRIVACY.md](PRIVACY.md) for exactly what is sent). Pooled feedback from many players trains a model of what players
reward, which is used to train better dog brains at scale; new versions are published here as releases and installed
with `/ledog update`. Every release must still pass the realism and safety checks the dogs were built with.

## Repository

- `mod/`: the Fabric mod (Java). Build with `cd mod && ./gradlew build`.
- `collector/`: the community-training collector (Cloudflare Worker + D1).

Code and released dog brains: MIT License. The research pipeline that produced the dogs is not part of this repository.
