// Local presentation aid. Enabled only by an explicit development URL flag.
export function installDemoPointer() {
  const query = new URLSearchParams(location.search)
  if (query.get('demoPointer') === '1') sessionStorage.setItem('school-demo-pointer', '1')
  if (query.get('demoPointer') === '0') sessionStorage.removeItem('school-demo-pointer')
  if (sessionStorage.getItem('school-demo-pointer') !== '1') return
  const pointer = document.createElement('div')
  pointer.setAttribute('aria-hidden', 'true')
  pointer.style.cssText = 'position:fixed;left:50px;top:50px;z-index:2147483647;pointer-events:none;transition:left .25s ease-out,top .25s ease-out;filter:drop-shadow(0 2px 3px #fff)'
  pointer.innerHTML = '<svg width="30" height="38" viewBox="0 0 30 38" style="width:30px;height:38px"><path d="M3 2 L3 29 L10 22 L17 35 L23 32 L16 20 L27 20 Z" fill="#ef4444" stroke="white" stroke-width="2"/></svg>'
  document.body.append(pointer)
  const badge = document.createElement('div')
  badge.textContent = '红色指针 = 当前点击位置'
  badge.style.cssText = 'position:fixed;bottom:12px;right:14px;z-index:2147483646;background:#fff1f2;color:#be123c;border:1px solid #fda4af;border-radius:8px;padding:8px 12px;font:12px Microsoft YaHei;pointer-events:none'
  document.body.append(badge)
  const move = (event: PointerEvent) => { pointer.style.left = event.clientX + 'px'; pointer.style.top = event.clientY + 'px' }
  const down = (event: PointerEvent) => {
    move(event)
    const ring = document.createElement('div')
    ring.style.cssText = `position:fixed;left:${event.clientX - 20}px;top:${event.clientY - 20}px;width:40px;height:40px;border:3px solid #ef4444;border-radius:50%;background:#ef444422;z-index:2147483646;pointer-events:none`
    document.body.append(ring)
    ring.animate([{transform:'scale(.5)',opacity:1},{transform:'scale(1.8)',opacity:0}],{duration:1600}).onfinish = () => ring.remove()
  }
  window.addEventListener('pointermove', move, true)
  window.addEventListener('pointerdown', down, true)
  if (import.meta.hot) import.meta.hot.dispose(() => { pointer.remove(); badge.remove(); window.removeEventListener('pointermove', move, true); window.removeEventListener('pointerdown', down, true) })
}
