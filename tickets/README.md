Work that is queued but not done

One file per piece of work. The **first line is the title** and everything after it is whatever the
work needs — a description, a design, a plan, the reasoning that led to it, links to what it touches.

    0007-term-tty-detection.md

The number orders them and the slug says what it is, so `ls` answers "what is queued" without opening
anything. The first line is plain text rather than a `#` heading because a ticket's title is usually
the commit message's first line too, and `#` is a comment to git.

**A ticket is deleted when the work lands.** Not moved to a `done/`, not marked closed: git holds the
text, and the commit that removes it is the commit that did the work. What that buys is that
`ls tickets/` is *always* the queue and never needs filtering — a folder of mostly-finished tickets
tells you nothing at a glance, which is how these rot.

**This is the queue; the auto-memory notes are the record.** A ticket says what has not been done
yet. What happened, why a decision went the way it did, and what a future session must not
re-derive belong in memory, or in `CLAUDE.md` where they are a standing rule about the project.
Nothing should be in both.

The point of the folder is **batching**: the release process is long enough that changes are grouped
into one, and a queue that lives in conversation cannot be grouped by anyone who was not in the
conversation.
