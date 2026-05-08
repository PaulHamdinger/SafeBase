# SafeBase : Create safe zones with vanilla blocks!

**SafeBase** lets you create a safe zone for just you and your friends. Griefers can't get in!

## How it works

Place a **lectern** on top of a **block of white wool**, then add a **book-and-quill** to the lectern. That's all it takes to create a zone of protection around the lectern. You'll see particles start to swirl nearby.

![SafeBase particle ring effect](https://github.com/PaulHamdinger/SafeBase/blob/main/screenshots/screenshot-01.jpg?raw=true)

By default _only you can enter_ your SafeBase. The safe zone takes the shape of a square, **128 blocks across**, centered around the lectern. It extends the entire height of the world.

_Note : Each player must first get permission to use SafeBase. Have an op run `/safebase allow` for you._

## Allow only your friends inside

Write the names of your friends in the book-and-quill, then place it on the lectern. Now _they'll also be able to enter_ your SafeBase.

You don't need to add your own name (the person who places the book-and-quill is always allowed into the SafeBase).

## Allow everyone (except griefers) inside

Use a _black block of wool_ instead of white under the lectern. Now the list of names in the book becomes a "blacklist" -- in other words, a list of players who _cannot_ enter (but everyone else can).

## How is a SafeBase protected?

When a _forbidden_ player approaches your SafeBase (within 32 blocks) they'll be shown a warning. When they get close enough to actually cross into your SafeBase, they are instantly nudged back outside.

The forbidden player cannot use Ender Pearls, Nether Portals, Chorus Fruit, etc to get inside. All teleportation options are cancelled by the server.

If -- somehow -- they do manage to get inside anyway, a 2-second safeguard timer will always kick them out.

## Customise the zone size

You can add up to _8 more blocks_ of wool next to the original block, to form a 3x3 platform. The additional wool should be the same colour as the original block (ie, white or black). Each _additional_ block of wool expands your SafeBase by another 64 blocks across. The maximum size of a SafeBase is therefore 640 blocks across, when using the maximum 9 pieces of wool.

## For Admins and Ops

Ops bypass all SafeBase enforcement.

Only permitted players can use SafeBase. Ops _opt-in_ players to SafeBase with the `/safebase allow` command. Or the server owner can manually edit config.yml after first launch.

Ops also have commands for listing and disabling SafeBases. See `/safebase help` for the full list of options.

A config file controls zone geometry, warning cooldowns, and the particle effect (beacon beam, colored column, rotating ring, or shield dome).

## Technical

This plugin works on Paper 1.21. Zones and config are stored as simple YAML files. There are no other plugin dependencies.

The list of SafeBases persists across server reboots. If there's ever a conflict between the on-disk info and the in-game info, the SafeBase is disabled.

SafeBases can be "reset", in the case of problems, by removing and re-placing the book-and-quill.

## Thanks

Thank you to the Nether Core team (Liam K) for assisting in the development of this plugin; and also to Cash for being a donk who wouldn't stop stealing stuff from my base.

