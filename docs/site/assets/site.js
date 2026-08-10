/* Zodiac docs — the only script on the site.
   Three jobs: theme toggle, narrow-screen nav, "on this page" list.
   Everything degrades to a usable page with JS off. No network access. */

(function () {
  "use strict";

  var root = document.documentElement;

  /* ---- theme ---------------------------------------------------------- */

  function readStored() {
    try {
      return window.localStorage.getItem("zodiac-theme");
    } catch (e) {
      return null; /* file:// storage can be blocked; not a problem */
    }
  }

  function store(value) {
    try {
      window.localStorage.setItem("zodiac-theme", value);
    } catch (e) {
      /* ignore */
    }
  }

  var stored = readStored();
  if (stored === "light" || stored === "dark") {
    root.setAttribute("data-theme", stored);
  }

  function currentTheme() {
    var explicit = root.getAttribute("data-theme");
    if (explicit) return explicit;
    return window.matchMedia &&
      window.matchMedia("(prefers-color-scheme: light)").matches
      ? "light"
      : "dark";
  }

  var themeBtn = document.querySelector("[data-theme-toggle]");
  if (themeBtn) {
    var paint = function () {
      var t = currentTheme();
      themeBtn.textContent = t === "dark" ? "Light" : "Dark";
      themeBtn.setAttribute(
        "aria-label",
        "Switch to " + (t === "dark" ? "light" : "dark") + " theme"
      );
    };
    paint();
    themeBtn.addEventListener("click", function () {
      var next = currentTheme() === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      store(next);
      paint();
    });
  }

  /* ---- narrow-screen navigation --------------------------------------- */

  var navBtn = document.querySelector("[data-nav-toggle]");
  var rail = document.getElementById("rail");
  if (navBtn && rail) {
    var narrow = window.matchMedia("(max-width: 60rem)");
    var sync = function () {
      if (narrow.matches) {
        rail.hidden = true;
        navBtn.setAttribute("aria-expanded", "false");
      } else {
        rail.hidden = false;
      }
    };
    sync();
    if (narrow.addEventListener) narrow.addEventListener("change", sync);
    navBtn.addEventListener("click", function () {
      rail.hidden = !rail.hidden;
      navBtn.setAttribute("aria-expanded", rail.hidden ? "false" : "true");
    });
  }

  /* ---- on this page --------------------------------------------------- */

  var toc = document.getElementById("toc");
  var page = document.querySelector(".page .prose");
  if (toc && page) {
    var heads = page.querySelectorAll("h2[id]");
    if (heads.length > 2) {
      var list = document.createElement("ol");
      Array.prototype.forEach.call(heads, function (h) {
        var li = document.createElement("li");
        var a = document.createElement("a");
        a.href = "#" + h.id;
        a.textContent = h.textContent;
        li.appendChild(a);
        list.appendChild(li);
      });
      var title = document.createElement("h2");
      title.textContent = "On this page";
      toc.appendChild(title);
      toc.appendChild(list);
    }
  }
})();
