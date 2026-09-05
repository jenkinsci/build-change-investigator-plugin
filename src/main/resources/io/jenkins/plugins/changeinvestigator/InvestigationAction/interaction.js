(function () {
  function ready() {
    var navigation=document.querySelector('.bci-native-navigation');
    if(navigation && window.matchMedia){
      var narrow=window.matchMedia('(max-width:900px)');
      function updateNavigation(){navigation.open=!narrow.matches;}
      updateNavigation();
      if(narrow.addEventListener)narrow.addEventListener('change',updateNavigation);
    }
    document.querySelectorAll('[data-bci-open]').forEach(function (button) {button.addEventListener('click',function () {document.getElementById(button.dataset.bciOpen).showModal();});});
    document.querySelectorAll('[data-bci-close]').forEach(function (button) {button.addEventListener('click',function () {button.closest('dialog').close();});});
    document.querySelectorAll('[data-bci-select]').forEach(function (button) {button.addEventListener('click',function () {document.querySelectorAll('[data-bci-record]').forEach(function (record) {record.hidden=record.dataset.bciRecord!==button.dataset.bciSelect;});button.closest('dialog').close();document.querySelector('[data-bci-record="'+button.dataset.bciSelect+'"]').scrollIntoView({block:'nearest'});});});
    var copy=document.getElementById('bci-copy');
    if(copy)copy.addEventListener('click',function () {
      var bundle=document.getElementById('bci-bundle');
      function fallback(){bundle.hidden=false;bundle.focus();bundle.select();document.getElementById('bci-copy-status').textContent='Select and copy the investigation below.';}
      if(!navigator.clipboard){fallback();return;}
      navigator.clipboard.writeText(bundle.value).then(function(){document.getElementById('bci-copy-status').textContent='Investigation copied';},fallback);
    });
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',ready);else ready();
}());
