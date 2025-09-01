package dev.apiguard;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ReportController {
  private final RunService runs;
  private final PlatformStore store;

  public ReportController(RunService runs, PlatformStore store) {
    this.runs = runs;
    this.store = store;
  }

  @GetMapping("/reports/{id}")
  public String report(@PathVariable String id, Model model) {
    var details = runs.details(id);
    model.addAttribute("run", details.run());
    model.addAttribute("results", details.results());
    model.addAttribute("history", store.runs(details.run().suiteId(), 20));
    return "report";
  }
}
