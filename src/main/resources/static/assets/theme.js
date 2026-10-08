// Loaded in <head> so a chosen theme applies before the first paint. No choice stored = follow the system.
(() => {
  const KEY = "hufly-theme";
  const COLORS = { light: "#F8F9FE", dark: "#111417" };
  const root = document.documentElement;
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)");

  const stored = () => { try { return localStorage.getItem(KEY); } catch { return null; } };
  const store = (theme) => {
    try { theme ? localStorage.setItem(KEY, theme) : localStorage.removeItem(KEY); } catch {}
  };
  const effective = () => root.dataset.theme || (systemDark.matches ? "dark" : "light");

  const apply = (theme) => {
    if (theme === "light" || theme === "dark") root.dataset.theme = theme;
    else delete root.dataset.theme;
    document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => {
      if (!meta.dataset.system) meta.dataset.system = meta.content;
      meta.content = root.dataset.theme ? COLORS[root.dataset.theme] : meta.dataset.system;
    });
    const label = effective() === "dark" ? "Helles Design" : "Dunkles Design";
    document.querySelectorAll(".theme-toggle").forEach((button) => {
      button.setAttribute("aria-label", label);
      button.title = label;
    });
  };

  apply(stored());

  document.addEventListener("DOMContentLoaded", () => {
    apply(stored());
    document.querySelectorAll(".theme-toggle").forEach((button) => button.addEventListener("click", () => {
      const next = effective() === "dark" ? "light" : "dark";
      // Picking what the system shows anyway means following the system again.
      const choice = next === (systemDark.matches ? "dark" : "light") ? null : next;
      store(choice);
      apply(choice);
    }));
  });
  systemDark.addEventListener("change", () => apply(root.dataset.theme));
})();
