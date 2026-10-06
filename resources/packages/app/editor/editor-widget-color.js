// The graph owns the controls; this widget synchronizes one text value with
// native RGB and alpha inputs. Collection and type validation remain generic.
window.GraphdenFormWidgets = window.GraphdenFormWidgets || {};
window.GraphdenFormWidgets.color = {
  mount(el, value) {
    const field = el.querySelector('[data-form-field]');
    const picker = el.querySelector('input[type="color"]');
    const alpha = el.querySelector('[data-color-alpha]');
    const preview = el.querySelector('rect');
    const type = JSON.parse(el.getAttribute('data-form-type'));
    const path = el.getAttribute('data-field-path');
    if (path) field.setAttribute('data-field-path', path);

    const sync = () => {
      const valid = field.value !== '' && validateLiteralAgainstType(field.value, type).ok;
      field.setCustomValidity(valid ? '' : 'Enter a HEX color: #RGB, #RGBA, #RRGGBB or #RRGGBBAA.');
      preview.setAttribute('fill', valid ? field.value : 'none');
      if (!valid) return;
      let hex = field.value.slice(1);
      if (hex.length <= 4) hex = [...hex].map(c => c + c).join('');
      picker.value = '#' + hex.slice(0, 6);
      alpha.value = String(hex.length === 8 ? Number.parseInt(hex.slice(6), 16) : 255);
    };
    const pick = () => {
      const opacity = Math.max(0, Math.min(255, Number(alpha.value)));
      const hadAlpha = field.value.length === 5 || field.value.length === 9;
      field.value = picker.value + (opacity !== 255 || hadAlpha
        ? opacity.toString(16).padStart(2, '0') : '');
      sync();
      field.dispatchEvent(new Event('input', {bubbles: true}));
    };
    field.value = typeof value === 'string' ? value : '';
    field.addEventListener('input', sync);
    picker.addEventListener('input', pick);
    alpha.addEventListener('input', pick);
    sync();
  }
};
