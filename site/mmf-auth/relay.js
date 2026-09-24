// MyMiniFactory redirects here with the sign-in result in the URL fragment (or ?error=... on refusal). This only forwards
// that string to the Nozzle It All app's own deep link; nothing is sent to any server, stored, or logged.
(function () {
  // Custom-scheme URIs can lose their #fragment on the way to the app, so everything travels as a query string.
  var parts = [location.search.replace(/^\?/, ""), location.hash.replace(/^#/, "")].filter(Boolean);
  var target = "nozzleitall://mmf-auth" + (parts.length ? "?" + parts.join("&") : "");
  var open = document.getElementById("open");
  open.href = target;
  if (/access_token=|error=/.test(location.hash + location.search)) {
    location.replace(target);
    setTimeout(function () { document.getElementById("msg").textContent = "Tap the button below to finish signing in."; }, 1500);
  } else {
    document.getElementById("msg").textContent = "Nothing to hand back. Start the sign-in from the app.";
  }
})();
