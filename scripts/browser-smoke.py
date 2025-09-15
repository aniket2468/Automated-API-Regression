"""Optional browser smoke test. Requires Playwright and installed Google Chrome.

Install: python -m pip install playwright
Run with the demo profile active: python scripts/browser-smoke.py
"""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    args = parser.parse_args()
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="chrome", headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1100}, device_scale_factor=1)
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(args.url)
        expect(page.get_by_role("heading", name="Your API. Under guard.")).to_be_visible()

        def import_spec(name, filename):
            page.locator("#spec-name").fill(name)
            page.locator("#spec-file").set_input_files(ROOT / "examples" / filename)
            page.get_by_role("button", name="Import specification").click()
            expect(page.locator("#notice")).to_contain_text(f"Imported {name}")
            return page.locator("#generate-spec").input_value()

        baseline = import_spec("Browser baseline", "catalog-v1.yaml")
        page.locator("#suite-name").fill("Browser regression")
        page.get_by_role("button", name="Generate test suite").click()
        expect(page.locator("#test-list .item")).to_have_count(3)
        suite_id = page.locator("#run-suite").input_value()
        page.locator("#test-list").get_by_role("button", name="Configure").first.click()
        config = json.loads(page.locator("#config-json").input_value())
        page.locator("#config-json").fill("{")
        page.get_by_role("button", name="Save configuration").click()
        expect(page.locator("#edit-error")).to_be_visible()
        config["maxLatencyMs"] = 10000
        config["assertions"] = [{"pointer": "/0/name", "expected": "Mechanical keyboard"}]
        page.locator("#config-json").fill(json.dumps(config, indent=2))
        page.get_by_role("button", name="Save configuration").click()
        expect(page.locator("#editor")).not_to_be_visible()
        page.locator("#base-url").fill(args.url.rstrip("/") + "/demo-api")
        page.get_by_role("button", name="Run regression suite").click()
        expect(page.locator("#run-list tr").first).to_contain_text("PASSED", timeout=30000)

        candidate = import_spec("Browser candidate", "catalog-v2.yaml")
        page.locator("#old-spec").select_option(baseline)
        page.locator("#new-spec").select_option(candidate)
        page.locator("#impact-suite").select_option(suite_id)
        page.get_by_role("button", name="Analyze changes").click()
        expect(page.locator("#impact-results")).to_contain_text("3 affected existing tests")
        page.locator("#base-url").fill(args.url.rstrip("/") + "/demo-v2")
        page.get_by_role("button", name="Run affected baseline tests").click()
        expect(page.locator("#run-list tr").first).to_contain_text("FAILED", timeout=30000)
        screenshot_dir = ROOT / "reports"
        screenshot_dir.mkdir(exist_ok=True)
        page.evaluate("window.scrollTo(0, 0)")
        page.screenshot(path=str(screenshot_dir / "dashboard.png"), full_page=True)
        report_url = page.locator("#run-list tr").first.get_by_role("link", name="Report").get_attribute("href")
        page.goto(args.url.rstrip("/") + report_url)
        expect(page.get_by_role("heading", name="Test results")).to_be_visible()
        expect(page.locator("body")).to_contain_text("schema")
        page.screenshot(path=str(screenshot_dir / "failure-report.png"), full_page=True)
        page.goto(args.url)
        page.set_viewport_size({"width": 390, "height": 844})
        expect(page.get_by_role("heading", name="Your API. Under guard.")).to_be_visible()
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), "Mobile layout overflows"
        page.screenshot(path=str(screenshot_dir / "mobile.png"), full_page=True)
        assert not errors, errors
        browser.close()
        print("Browser smoke passed: import, generation, config editor, runs, impact, reports, mobile layout.")


if __name__ == "__main__":
    main()
