import {Component,type ReactNode} from 'react';
interface Props {children:ReactNode}
interface State {error:string|null;attempt:number}
export class CyberpunkErrorBoundary extends Component<Props,State> {
  state:State={error:null,attempt:0};
  static getDerivedStateFromError(error:unknown):Partial<State>{return {error:error instanceof Error?error.message:'Unexpected panel error'}}
  render(){
    if(this.state.error)return <section className="notice alert-error" role="alert"><h2>Panel unavailable</h2><p>{this.state.error}</p><p>The panel failed to render. Background experiments continue independently; reloading this panel does not stop them.</p><button onClick={()=>this.setState(state=>({error:null,attempt:state.attempt+1}))}>Reload panel</button></section>;
    return <div key={this.state.attempt}>{this.props.children}</div>;
  }
}
