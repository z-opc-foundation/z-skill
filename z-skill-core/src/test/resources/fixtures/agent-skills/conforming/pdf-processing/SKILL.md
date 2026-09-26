---
name: pdf-processing
description: Extract PDF text, fill forms, merge files. Use when handling PDFs.
license: Apache-2.0
compatibility: Requires Python 3.11 and pypdf. Works in Claude Code and Cursor.
metadata:
  author: example-org
  version: "1.2.0"
  category: documents
  tags: pdf, documents
allowed-tools: Bash(python:*) Read Write
---
# PDF processing

Extract text and fill forms.

## Steps
1. Run `scripts/extract.py <file>`
2. See `references/notes.md` for edge cases
