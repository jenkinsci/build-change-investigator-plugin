Behaviour.specify('#mapping-add', 'bci-mapping-editor', 0, function (link) {
  const form = document.forms.mapping;
  const identity = document.getElementById('mapping-identity');
  const heading = document.getElementById('mapping-editor');
  if (!form || !identity || !heading) return;
  const fields = [
    [identity, 'mapping-identity-error', 'Enter a Git or Jenkins identity of at most 256 characters.'],
    [form.elements.namedItem('slackUser'), 'mapping-member-error', 'Enter a valid Slack user ID.']
  ];
  fields.forEach(([input, errorId, message]) => {
    if (document.getElementById(errorId)) {
      input.setAttribute('aria-invalid', 'true');
      input.setAttribute('aria-describedby', errorId);
    }
    input.addEventListener('invalid', function (event) {
      event.preventDefault();
      let error = document.getElementById(errorId);
      if (!error) {
        error = document.createElement('div');
        error.id = errorId;
        error.className = 'error';
        error.setAttribute('role', 'alert');
        error.setAttribute('data-mapping-error', 'true');
        input.insertAdjacentElement('afterend', error);
      }
      error.textContent = message;
      input.setAttribute('aria-invalid', 'true');
      input.setAttribute('aria-describedby', errorId);
      form.querySelector('[aria-invalid="true"]').focus();
    });
  });
  link.addEventListener('click', function (event) {
    event.preventDefault();
    form.elements.namedItem('id').value = '';
    identity.value = '';
    form.elements.namedItem('slackUser').value = '';
    heading.textContent = 'Add mapping';
    form.querySelectorAll('[data-mapping-error]').forEach(error => error.remove());
    form.querySelectorAll('[aria-invalid]').forEach(input => {
      input.removeAttribute('aria-invalid');
      input.removeAttribute('aria-describedby');
    });
    const submit = form.querySelector('button[type="submit"]');
    if (submit) submit.textContent = 'Save mapping';
    window.history.replaceState(null, '', '#mapping-editor');
    heading.scrollIntoView();
    identity.focus({ preventScroll: true });
  });
  if (window.location.hash === '#mapping-editor') identity.focus({ preventScroll: true });
});
