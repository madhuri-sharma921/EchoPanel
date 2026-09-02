#!/usr/bin/env bash
#
# First-time setup for the EchoPanel backend: creates a virtualenv,
# installs dependencies, and copies the .env template if one doesn't
# already exist. Safe to re-run. Works on Linux/macOS and Windows
# (Git Bash / MSYS), where the venv layout differs (Scripts/ vs bin/).
set -euo pipefail

cd "$(dirname "$0")/backend"

# Prefer python3 if present (Linux/macOS), fall back to python (Windows).
PYTHON=python3
if ! command -v python3 >/dev/null 2>&1; then
    PYTHON=python
fi

if [ ! -d ".venv" ]; then
    echo "Creating virtualenv at backend/.venv ..."
    "$PYTHON" -m venv .venv
fi

# Windows venvs put executables in Scripts/, not bin/.
if [ -f ".venv/Scripts/pip.exe" ]; then
    VENV_PIP=".venv/Scripts/pip.exe"
else
    VENV_PIP=".venv/bin/pip"
fi

echo "Installing dependencies ..."
"$VENV_PIP" install --upgrade pip --quiet
"$VENV_PIP" install -r requirements.txt --quiet

if [ ! -f ".env" ]; then
    echo "Creating backend/.env from .env.example — fill in your API keys."
    cp .env.example .env
else
    echo "backend/.env already exists — leaving it untouched."
fi

echo
echo "Setup complete. Next steps:"
echo "  1. Edit backend/.env with your OPENAI_API_KEY and Agora credentials"
echo "  2. make run    # start the backend"
echo "  3. make test   # run the test suite"
