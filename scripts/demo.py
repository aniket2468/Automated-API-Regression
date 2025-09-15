"""Exercise the real HTTP workflow. Standard library only; start the demo profile first."""
import argparse
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    args = parser.parse_args()
    base = args.url.rstrip("/")

    def api(path, body=None, method=None):
        data = None if body is None else json.dumps(body).encode()
        request = Request(base + "/api" + path, data=data, method=method,
                          headers={"Content-Type": "application/json"})
        with urlopen(request, timeout=30) as response:
            return json.load(response)

    def wait(run):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            detail = api("/runs/" + run["id"])
            if detail["run"]["status"] not in ("QUEUED", "RUNNING"):
                return detail
            time.sleep(0.3)
        raise RuntimeError("Run did not finish within 90 seconds")

    def run_suite(suite_id, target, candidate=None):
        body = {"suiteId": suite_id, "baseUrl": base + target, "parallelism": 2, "timeoutMs": 5000}
        if candidate:
            body["compareToSpecId"] = candidate
        return wait(api("/runs", body))

    old = api("/specs", {"name": "Catalog API", "content": (ROOT / "examples/catalog-v1.yaml").read_text()})
    suite = api("/suites", {"specId": old["id"], "name": "Catalog regression"})
    for test in suite["tests"]:
        config = test["config"]
        config["maxLatencyMs"] = 10000  # Includes cold-start client initialization in a demo.
        if test["operation"] == "GET /products/{id}":
            config["assertions"] = [{"pointer": "/name", "expected": "Mechanical keyboard"}]
            config["expectedHeaders"] = {"X-API-Version": "1"}
        api(f'/suites/{suite["id"]}/tests/{test["id"]}', config, "PUT")
    baseline = run_suite(suite["id"], "/demo-api")
    if baseline["run"]["status"] != "PASSED":
        raise RuntimeError("Expected passing baseline: " + json.dumps(baseline))

    new = api("/specs", {"name": "Catalog API", "content": (ROOT / "examples/catalog-v2.yaml").read_text()})
    impact = api(f'/changes?oldSpecId={old["id"]}&newSpecId={new["id"]}&suiteId={suite["id"]}')
    changed = run_suite(suite["id"], "/demo-v2", new["id"])
    if changed["run"]["status"] != "FAILED" or changed["run"]["failed"] != 2:
        raise RuntimeError("Expected two contract failures: " + json.dumps(changed))

    report_dir = ROOT / "reports"
    report_dir.mkdir(exist_ok=True)
    for name, result in (("baseline", baseline), ("contract-regression", changed)):
        report_url = base + "/reports/" + result["run"]["id"]
        with urlopen(report_url, timeout=30) as response:
            (report_dir / f"{name}.html").write_bytes(response.read())
        print(f'{name}: {result["run"]["status"]} - {report_url}')
    (report_dir / "impact.json").write_text(json.dumps(impact, indent=2))
    print(f'{len(impact["affectedTestIds"])} affected existing tests; '
          f'{len(impact["addedOperations"])} added operation. Reports: {report_dir}')


if __name__ == "__main__":
    try:
        main()
    except (HTTPError, URLError, RuntimeError) as error:
        raise SystemExit(f"Demo failed: {error}. Start the application with the demo profile.") from error
