.PHONY: setup run test lint clean android-build android-test help

VENV := backend/.venv/bin

help:
	@echo "EchoPanel — common tasks:"
	@echo "  make setup          First-time backend setup (venv, deps, .env)"
	@echo "  make run            Run the backend API (http://localhost:8000)"
	@echo "  make test           Run backend tests"
	@echo "  make clean          Remove caches and build artifacts"
	@echo "  make android-build  Build the Android debug APK (needs Android SDK)"
	@echo "  make android-test   Run Android unit tests"

setup:
	./setup.sh

run:
	cd backend && $(PWD)/$(VENV)/uvicorn app.main:app --reload

test:
	cd backend && $(PWD)/$(VENV)/pytest tests/ -v

clean:
	find backend -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find backend -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
	rm -rf android/app/build android/build

android-build:
	cd android && ./gradlew assembleDebug

android-test:
	cd android && ./gradlew testDebugUnitTest
