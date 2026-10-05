import {useEffect,useId,useRef} from 'react';
import type {ReactNode} from 'react';

export type IconName = 'monitor'|'experiment'|'simulation'|'recommendation'|'results'|'gameshield'|'environment'|'key'|'close'|'arrow';
const paths:Record<IconName,string>={
  monitor:'M3 12h4l3-7 4 14 3-7h4 M3 3v18h18',
  experiment:'M9 3h6 M10 3v6L4 19a1 1 0 0 0 1 2h14a1 1 0 0 0 1-2L14 9V3 M8 14h8',
  simulation:'M4 5h16v4H4z M4 15h7v4H4z M15 15h5v4h-5z M12 9v3 M7 15v-3h11v3',
  recommendation:'M12 3v3 M12 18v3 M3 12h3 M18 12h3 M5.6 5.6l2 2 M16.4 16.4l2 2 M5.6 18.4l2-2 M16.4 7.6l2-2 M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  results:'M6 3h12v18H6z M9 7h6 M9 11h6 M9 15h3',
  gameshield:'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M8 12l3 3 5-6',
  environment:'M6 6h12v12H6z M9 9h6v6H9z M9 2v4 M15 2v4 M9 18v4 M15 18v4 M2 9h4 M2 15h4 M18 9h4 M18 15h4',
  key:'M14 8a5 5 0 1 1-4-5 5 5 0 0 1 4 5z M13 12l8 8 M17 16l3-3',
  close:'M6 6l12 12 M18 6L6 18',arrow:'M5 12h14 M14 7l5 5-5 5'
};
export function Icon({name}:{name:IconName}){return <svg className="ui-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]}/></svg>}
export function Provenance({kind}:{kind:'MEASURED'|'DERIVED'|'SIMULATED'|'RECORDED'}){return <span className={`source-tag source-${kind.toLowerCase()}`}>{kind}</span>}
export function EmptyState({title,children}:{title:string;children:ReactNode}){return <div className="empty-state"><span className="empty-cross" aria-hidden="true">+</span><h3>{title}</h3><p>{children}</p></div>}
export function Dialog({title,onClose,children,busy=false,drawer=false}:{title:string;onClose:()=>void;children:ReactNode;busy?:boolean;drawer?:boolean}){
  const ref=useRef<HTMLDialogElement>(null);const titleId=useId();
  useEffect(()=>{const node=ref.current;const previous=document.activeElement;node?.showModal();node?.querySelector<HTMLElement>('[autofocus], [data-autofocus]')?.focus();return()=>{node?.close();if(previous instanceof HTMLElement)previous.focus()}},[]);
  return <dialog ref={ref} className={`cyber-dialog ${drawer?'drawer-dialog':''}`} aria-labelledby={titleId} onCancel={e=>{e.preventDefault();if(!busy)onClose()}}>
    <div className="dialog-heading"><div><div className="eyebrow">SCHEDWISE CONTROL</div><h2 id={titleId}>{title}</h2></div><button type="button" className="icon-button" aria-label="Close dialog" disabled={busy} onClick={onClose}><Icon name="close"/></button></div>{children}
  </dialog>;
}
