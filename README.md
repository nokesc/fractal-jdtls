# Fractal JDT LS

A verified install of the Eclipse JDT Language Server (JDT LS) that works when several Git worktrees of the same Maven project share one server.

## Why it exists

Coding agents, and the developers working alongside them, increasingly work in several Git worktrees of one repository at once. Each worktree is a full checkout of the same project, on its own branch.

JDT LS gives editors and agents Java code intelligence: compile errors as you type, go-to-definition, references and refactoring. It understands Maven projects through m2e, Eclipse's Maven integration, which ships inside it. JDT LS and m2e were built around one checkout of a project per workspace, and they break down when there are several:

- **One server can't hold two checkouts of the same multi-module project.** The modules have the same names and Maven coordinates in every worktree. They collide, and one worktree's code can be resolved against another's.
- **The alternative is one server per worktree,** which costs more than a gigabyte of memory each, plus a long first import.
- **The stock launcher identifies a workspace by its folder name,** so two checkouts with the same folder name share one workspace's state.
- **Installing is manual.** JDT LS is a download you unpack and launch yourself, and part of what it contains can be verified only by the download's checksum.

## What it provides

- **An installer** that downloads an official Eclipse JDT LS release, never a snapshot or milestone, from Eclipse, and verifies it before use. This project never repackages or redistributes Eclipse's files.
- **A launcher that drop-in replaces `jdtls`.** It starts the verified release with a small Java agent that keeps each worktree's projects separate inside one server.
- **Releases that pin a tested combination:** one JDT LS version and the agent, tested together.

It's deliberately unopinionated. How many servers to run, which worktrees share one, and when they start and stop are left to the tool that launches it.

## Status

Early. Linux only. The aim is for Eclipse to fix these problems upstream, so that this project becomes just a verified installer, or is no longer needed. Contributions are welcome.
