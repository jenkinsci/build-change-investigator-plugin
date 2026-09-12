(function () {
  "use strict";
  function initialize() {
    const form = document.getElementById("bci-feedback-form");
    if (!form) return;
    const action = form.elements.action;
    const confirmation = document.getElementById("bci-confirmation-reference");
    const resolutionSelected = () => confirmation.selectedOptions[0]?.dataset.resolution === "true";
    const result = document.getElementById("bci-review-result");
    const refresh = document.getElementById("bci-review-refresh");
    const descriptions = {
      ACKNOWLEDGE: "I am looking at this investigation. This does not assign ownership, confirm a cause, or mark it resolved.",
      NOT_RELATED: "Attach a human review to this candidate. The original SCM facts and deterministic ranking are preserved.",
      CONFIRM_CAUSE: "Confirm a human-reviewed cause. This does not mean the issue is fixed.",
      CONFIRM_RESOLUTION: "Confirmation records a human-reviewed resolution. It does not change the original build evidence.",
      REVOKE_CONFIRMATION: "Withdraw the current assertion with an explicit reason. The earlier record remains in history.",
      CORRECT_CONFIRMATION: "Replace the current assertion with a reviewed correction. The earlier record remains in history.",
      REOPEN: "Reopen is allowed only for an administratively closed unresolved case with sufficient retained evidence.",
      MUTE: "Mute notification delivery only. Evidence collection and the build result are unchanged.",
      UNMUTE: "Allow future eligible notifications. Suppressed updates will not be replayed."
    };
    function update() {
      form.querySelectorAll("[data-review-fields]").forEach(group => {
        const enabled = group.dataset.reviewFields.split(" ").includes(action.value);
        group.hidden = !enabled;
        group.querySelectorAll("input,select").forEach(field => { field.disabled = !enabled; field.required = false; });
      });
      if (action.value === "CORRECT_CONFIRMATION" && !resolutionSelected()) {
        document.getElementById("bci-fix-fields").hidden = true;
        document.getElementById("bci-fix-commit").disabled = true;
      }
      document.getElementById("bci-action-description").textContent = descriptions[action.value] || "";
      document.getElementById("bci-review-candidate").required = action.value === "NOT_RELATED";
      const confirm = ["CONFIRM_CAUSE", "CONFIRM_RESOLUTION", "CORRECT_CONFIRMATION"].includes(action.value);
      document.getElementById("bci-corrective-action").required = confirm;
      document.getElementById("bci-validation-basis").required = confirm;
      document.getElementById("bci-revoke-basis").required = ["REVOKE_CONFIRMATION", "REOPEN"].includes(action.value);
      document.getElementById("bci-review-note").required = ["MUTE", "REOPEN"].includes(action.value);
    }
    const requested = new URLSearchParams(window.location.search).get("reviewAction");
    if (Array.from(action.options).some(o => o.value === requested)) action.value = requested;
    const candidate = document.getElementById("bci-review-candidate");
    const requestedCandidate = new URLSearchParams(window.location.search).get("candidateId");
    if (Array.from(candidate.options).some(o => o.value === requestedCandidate)) candidate.value = requestedCandidate;
    update();
    action.addEventListener("change", update);
    confirmation.addEventListener("change", update);
    let pending = null;
    form.addEventListener("submit", async event => {
      event.preventDefault();
      if (!form.reportValidity()) return;
      const fields = new URLSearchParams(new FormData(form));
      fields.set("caseId", form.dataset.case);
      fields.set("expectedRevision", form.dataset.revision);
      fields.set("evidenceRevision", form.dataset.evidence);
      if (action.value === "CONFIRM_RESOLUTION" || (action.value === "CORRECT_CONFIRMATION" && resolutionSelected())) fields.set("recoveryBuild", form.dataset.recovery);

      const payload = fields.toString();
      if (pending && pending.payload !== payload) {
        result.textContent = "The previous submission has an unknown outcome. Refresh and review the latest history before submitting another action.";
        refresh.hidden = false;
        return;
      }
      if (!pending) {
        const bytes = new Uint8Array(16);
        crypto.getRandomValues(bytes);
        bytes[6] = (bytes[6] & 15) | 64;
        bytes[8] = (bytes[8] & 63) | 128;
        const hex = Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
        pending = {payload, nonce: [hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join("-")};
      }
      fields.set("actionId", pending.nonce);
      const button = form.querySelector("button[type=submit]");
      button.disabled = true;
      result.textContent = "Recording review…";
      try {
        const headers = {"Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"};
        if (window.crumb) window.crumb.wrap(headers);
        const response = await fetch(form.getAttribute("action"), {method: "POST", body: fields, headers, credentials: "same-origin", redirect: "error"});
        if (response.ok) {
          result.textContent = "Human review recorded. Refresh to see the current investigation.";
          refresh.hidden = false;
          form.querySelectorAll("input,select,button").forEach(field => { field.disabled = true; });
          return;
        }
        result.textContent = response.status === 409
          ? "This investigation changed while you were reviewing it. Refresh and review the latest evidence."
          : response.status === 403 ? "Permission denied. Your current Jenkins permissions or security token do not allow this action."
          : "Review could not be recorded. Refresh and check the current evidence, references, and required fields.";
        refresh.hidden = false;
        pending = null;
      } catch (error) {
        result.textContent = "The submission outcome is unknown. Retry the same action or refresh to inspect the review history.";
        refresh.hidden = false;
      }
      button.disabled = false;
    });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", initialize);
  else initialize();
})();
