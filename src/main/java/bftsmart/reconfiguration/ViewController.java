/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.reconfiguration;

import bftsmart.reconfiguration.util.TOMConfiguration;
import bftsmart.reconfiguration.views.DefaultViewStorage;
import bftsmart.reconfiguration.views.NodeNetwork;
import bftsmart.reconfiguration.views.View;
import bftsmart.reconfiguration.views.ViewStorage;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 *
 * @author eduardo
 */
public class ViewController {

    protected View lastView = null;
    protected View currentView = null;
    private TOMConfiguration staticConf;
    private ViewStorage viewStore;

//    public ViewController(int procId) {
//        this(new TOMConfiguration(procId));
//    }

    
//    public ViewController(int procId, String configHome) {
//        this(new TOMConfiguration(procId, configHome));
//    }
    
    public ViewController(TOMConfiguration config) {
    	this.staticConf = config;
    }
    
    public ViewController(TOMConfiguration config, ViewStorage viewSotrage) {
    	this.staticConf = config;
    	this.viewStore = viewSotrage;
    }

	public int getCurrentProcessId() {
		return this.getStaticConf().getProcessId();
	}
    
    public final ViewStorage getViewStore() {
        if (this.viewStore == null) {
            String className = staticConf.getViewStoreClass();
            try {
                this.viewStore = (ViewStorage) Class.forName(className).newInstance();
            } catch (Exception e) {
                this.viewStore = new DefaultViewStorage();
            }

        }
        return this.viewStore;
    }

    public View getCurrentView(){
        if(this.currentView == null){
             this.currentView = getViewStore().readView();
        }
        return this.currentView;
    }
    
    public View getLastView(){
        return this.lastView;
    }
    
    public NodeNetwork getRemoteAddress(int id) {
        return getCurrentView().getAddress(id);
    }

    public InetSocketAddress getRemoteSocketAddress(int id) {
        NodeNetwork nodeNetwork = getRemoteAddress(id);
        return new InetSocketAddress(nodeNetwork.getHost(), nodeNetwork.getConsensusPort());
    }
    
    public void reconfigureTo(View newView) {
        this.lastView = this.currentView;
        this.currentView = newView;
    }

    public TOMConfiguration getStaticConf() {
        return staticConf;
    }

    public boolean isCurrentViewMember(int id) {
        return getCurrentView().isMember(id);
    }

    public int getCurrentViewId() {
        return getCurrentView().getId();
    }

    public int getCurrentViewF() {
        return getCurrentView().getF();
    }

    public int getCurrentViewN() {
        return getCurrentView().getN();
    }

    public int getCurrentViewPos(int id) {
        return getCurrentView().getPos(id);
    }

    public int[] getCurrentViewProcesses() {
        return getCurrentView().getProcesses();
    }
}