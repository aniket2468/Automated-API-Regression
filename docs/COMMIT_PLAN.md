# Five-commit guide

The completed source is intentionally left uncommitted. Run the commands yourself after reviewing the files. These five groups fit the **current finished workspace**; each group adds a coherent deliverable without splitting Java dependencies into uncompilable commits.

September 1–November 1, 2025 can be used as a retrospective milestone timeline, but it does not represent when this code was created. Use actual commit dates. No history-rewriting commands are needed.

## 1. Working platform and tests

```powershell
git init
git branch -M main
git add .gitignore .gitattributes pom.xml mvnw mvnw.cmd .mvn src examples
git diff --cached --stat
git commit -m "feat: build API regression and contract testing platform"
```

Includes imports, generation, execution, reporting, dashboard, change impact, fixtures, and the test suite. Verify with `./mvnw.cmd verify` before committing. All runtime source is kept together because the controllers and integration tests exercise the complete workflow.

## 2. Reproducible local launch and demo

```powershell
git add scripts
git diff --cached --stat
git commit -m "feat: add local launcher and reproducible regression demo"
```

Verify with `./scripts/start.ps1 -Demo`, then `python scripts/demo.py` in another terminal.

## 3. Container packaging

```powershell
git add Dockerfile compose.yaml .dockerignore
git diff --cached --stat
git commit -m "build: package platform with Docker and persistent storage"
```

Verify with `docker compose up --build` when Docker Desktop is running.

## 4. Continuous verification

```powershell
git add .github
git diff --cached --stat
git commit -m "ci: verify regression tests and publish build artifacts"
```

This workflow executes on GitHub after pushing. Local tests do not prove that a hosted Actions job has run.

## 5. Project documentation

```powershell
git add README.md docs
git diff --cached --stat
git commit -m "docs: document architecture API usage and project workflow"
```

## Push when ready

Create an **empty** repository on your GitHub account (without a generated README), then:

```powershell
git remote add origin https://github.com/YOUR_USERNAME/api-regression-platform.git
git push -u origin main
```

Subsequent pushes use `git push`. Do not commit `data/`, `reports/`, `target/`, real credentials, or local editor files; the generated folders are already ignored.
