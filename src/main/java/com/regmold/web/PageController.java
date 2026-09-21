package com.regmold.web;

import com.regmold.store.ProjectStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PageController {

    private final ProjectStore store;

    public PageController(ProjectStore store) {
        this.store = store;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("projects", store.listProjects());
        return "index";
    }

    @GetMapping("/projects/{id}")
    public String project(@PathVariable long id, Model model) {
        model.addAttribute("project", store.project(id));
        model.addAttribute("revisions", store.listRevisions(id));
        return "project";
    }

    @GetMapping("/projects/{id}/revisions/{v}")
    public String revision(@PathVariable long id, @PathVariable int v, Model model) {
        model.addAttribute("project", store.project(id));
        model.addAttribute("revision", store.revision(id, v));
        return "revision";
    }

    @GetMapping("/projects/{id}/diff")
    public String diff(@PathVariable long id,
                       @RequestParam(required = false) Integer a,
                       @RequestParam(required = false) Integer b,
                       Model model) {
        model.addAttribute("project", store.project(id));
        model.addAttribute("revisions", store.listRevisions(id));
        model.addAttribute("a", a);
        model.addAttribute("b", b);
        return "diff";
    }
}
