Behaviour.specify('#bci-slack-workspace', 'bci-slack-workspace', 0, function (workspace) {
  const section = workspace.closest('section') || workspace.closest('.jenkins-section');
  if (!section) return;
  const credential = section.querySelector('select[name="_.credentialId"]');
  const channel = section.querySelector('input[name="_.defaultChannel"]');
  if (!credential || !channel) return;
  const name = workspace.querySelector('.bci-slack-workspace-name');
  const description = workspace.querySelector('.bci-slack-workspace-description');
  function clearResults() {
    section.querySelectorAll('.bci-slack-verification, .bci-slack-verification-failed')
      .forEach(function (result) { result.remove(); });
  }
  credential.addEventListener('change', function () {
    clearResults();
    name.textContent = 'Not verified yet';
    description.textContent = '';
  });
  channel.addEventListener('input', clearResults);
  channel.addEventListener('change', clearResults);
  const seen = new WeakSet();
  new MutationObserver(function () {
    section.querySelectorAll('.bci-slack-verification, .bci-slack-verification-failed').forEach(function (result) {
      if (seen.has(result)) return;
      seen.add(result);
      if (result.dataset.credential !== credential.value || result.dataset.channel !== channel.value.trim()) {
        result.remove();
        return;
      }
      const verified = result.classList.contains('bci-slack-verification');
      name.textContent = verified ? result.dataset.workspace : 'Not verified yet';
      description.textContent = verified ? 'Detected automatically' : '';
    });
  }).observe(section, { childList: true, subtree: true });
});
