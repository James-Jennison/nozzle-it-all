// MyMiniFactory redirects here with the sign-in result in the URL fragment (or ?error=... on refusal). This only forwards
// that string to the Nozzle It All app's own deep link; nothing is sent to any server, stored, or logged.
(function () {
  var target = "nozzleitall://mmf-auth" + (location.search || "") + (location.hash || "");
  var open = document.getElementById("open");
  open.href = target;
  if (/access_token=|error=/.test(location.hash + location.search)) {
    location.replace(target);
  } else {
    document.getElementById("msg").textContent = "Nothing to hand back. Start the sign-in from the app.";
  }
})();
