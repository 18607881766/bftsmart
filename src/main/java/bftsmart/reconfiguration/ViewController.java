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

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.reconfiguration.util.TOMConfiguration;
import bftsmart.reconfiguration.views.DefaultViewStorage;
import bftsmart.reconfiguration.views.NodeNetwork;
import bftsmart.reconfiguration.views.View;
import bftsmart.reconfiguration.views.ViewStorage;
import bftsmart.tom.ReplicaConfiguration;

/**
 *
 * @author eduardo
 */
public class ViewController implements ViewTopology {

	private static final Logger LOGGER = LoggerFactory.getLogger(ViewController.class);
	
    protected volatile View lastView = null;
    protected volatile View currentView = null;
    protected final TOMConfiguration staticConf;
    private ViewStorage viewStore;

    
    public ViewController(TOMConfiguration config, ViewStorage viewSotrage) {
    	this.staticConf = config;
    	this.viewStore = viewSotrage;
    	init();
    }


	private void init() {
		View cv = getViewStore().readView();
		if (cv == null) {

			LOGGER.debug("-- Creating current view from configuration file");
			reconfigureTo(new View(0, getStaticConf().getInitialView(), getStaticConf().getF(), getInitAdddresses()));
		} else {
			LOGGER.debug("-- Using view stored on disk");
			reconfigureTo(cv);
		}
	}
	
	private NodeNetwork[] getInitAdddresses() {

		int nextV[] = getStaticConf().getInitialView();
		NodeNetwork[] addresses = new NodeNetwork[nextV.length];
		for (int i = 0; i < nextV.length; i++) {
			addresses[i] = getStaticConf().getRemoteAddress(nextV[i]);
		}

		return addresses;
	}
    
	@Override
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

    @Override
	public View getCurrentView(){
        if(this.currentView == null){
             this.currentView = getViewStore().readView();
        }
        return this.currentView;
    }
    
    @Override
	public View getLastView(){
        return this.lastView;
    }
    
    @Override
	public NodeNetwork getRemoteAddress(int id) {
        return getCurrentView().getAddress(id);
    }

    @Override
	public InetSocketAddress getRemoteSocketAddress(int id) {
        NodeNetwork nodeNetwork = getRemoteAddress(id);
        return new InetSocketAddress(nodeNetwork.getHost(), nodeNetwork.getConsensusPort());
    }
    
    public synchronized void reconfigureTo(View newView) {
        this.lastView = this.currentView;
        this.currentView = newView;
    }

    @Override
	public ReplicaConfiguration getStaticConf() {
        return staticConf;
    }

    @Override
	public boolean isCurrentViewMember(int id) {
        return getCurrentView().isMember(id);
    }

    @Override
	public int getCurrentViewId() {
        return getCurrentView().getId();
    }

    @Override
	public int getCurrentViewF() {
        return getCurrentView().getF();
    }

    @Override
	public int getCurrentViewN() {
        return getCurrentView().getN();
    }

    @Override
	public int getCurrentViewPos(int id) {
        return getCurrentView().getPos(id);
    }

    @Override
	public int[] getCurrentViewProcesses() {
        return getCurrentView().getProcesses();
    }


}