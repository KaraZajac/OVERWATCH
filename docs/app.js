/* OVERWATCH landing — count-up stats + scroll reveal. No network, no deps. */
(function () {
  'use strict';
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* count-up numbers when the stats row scrolls into view */
  function countUp(el) {
    const target = parseInt(el.dataset.target, 10);
    if (isNaN(target)) return;
    if (!target || reduceMotion) { el.textContent = String(target); return; }
    const dur = 1100, start = performance.now();
    (function tick(now) {
      const p = Math.min(1, (now - start) / dur);
      el.textContent = String(Math.round(target * (1 - Math.pow(1 - p, 3))));
      if (p < 1) requestAnimationFrame(tick);
    })(performance.now());
  }

  const statsRow = document.getElementById('stats-row');
  if (statsRow) {
    const nums = statsRow.querySelectorAll('.num[data-target]');
    if ('IntersectionObserver' in window && !reduceMotion) {
      const io = new IntersectionObserver((entries, obs) => {
        entries.forEach((e) => {
          if (e.isIntersecting) { nums.forEach(countUp); obs.disconnect(); }
        });
      }, { threshold: 0.4 });
      io.observe(statsRow);
    } else {
      nums.forEach((el) => { el.textContent = el.dataset.target; });
    }
  }

  /* scroll-triggered reveal */
  const reveals = document.querySelectorAll('.reveal');
  if (!('IntersectionObserver' in window) || reduceMotion) {
    reveals.forEach((el) => el.classList.add('in'));
  } else {
    const ro = new IntersectionObserver((entries, obs) => {
      entries.forEach((e) => {
        if (e.isIntersecting) { e.target.classList.add('in'); obs.unobserve(e.target); }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
    reveals.forEach((el) => ro.observe(el));
  }
})();
