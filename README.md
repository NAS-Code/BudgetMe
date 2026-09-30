# Budget Categorizer

An Android app that notifies you the moment you make a purchase and lets you file it into your own
budget categories with one tap. It learns from your history and your taps, and uses an LLM only for
merchants it has never seen.

- Product spec and design decisions: [docs/SPEC.md](docs/SPEC.md)
- Data analysis and back-test results: [docs/FINDINGS.md](docs/FINDINGS.md)
- Status and next steps: [docs/ROADMAP.md](docs/ROADMAP.md)
- Context for Claude Code: [CLAUDE.md](CLAUDE.md)

Quick start (Python reference engine):
```
pip install -r requirements.txt
python engine/backtest.py
```
