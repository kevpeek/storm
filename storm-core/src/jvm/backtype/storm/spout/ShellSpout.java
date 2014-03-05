/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package backtype.storm.spout;

import backtype.storm.generated.ShellComponent;
import backtype.storm.metric.api.rpc.IShellMetric;
import backtype.storm.task.TopologyContext;
import backtype.storm.utils.ShellProcess;
import backtype.storm.utils.Utils;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.simple.JSONObject;


public class ShellSpout implements ISpout {
    public static Logger LOG = LoggerFactory.getLogger(ShellSpout.class);

    private SpoutOutputCollector _collector;
    private String[] _command;
    private ShellProcess _process;
    
    private Map<String, IShellMetric> _registeredShellMetrics = new ConcurrentHashMap<String, IShellMetric>();

    public ShellSpout(ShellComponent component) {
        this(component.get_execution_command(), component.get_script());
    }
    
    public ShellSpout(String... command) {
        _command = command;
    }
    
    public void open(Map stormConf, TopologyContext context,
                     SpoutOutputCollector collector) {
        _process = new ShellProcess(_command);
        _collector = collector;

        try {
            Number subpid = _process.launch(stormConf, context);
            LOG.info("Launched subprocess with pid " + subpid);
        } catch (IOException e) {
            throw new RuntimeException("Error when launching multilang subprocess\n" + _process.getErrorsString(), e);
        }
    }

    public void close() {
        _process.destroy();
    }

    private JSONObject _next;
    public void nextTuple() {
        if (_next == null) {
            _next = new JSONObject();
            _next.put("command", "next");
        }

        querySubprocess(_next);
    }

    private JSONObject _ack;
    public void ack(Object msgId) {
        if (_ack == null) {
            _ack = new JSONObject();
            _ack.put("command", "ack");
        }

        _ack.put("id", msgId);
        querySubprocess(_ack);
    }

    private JSONObject _fail;
    public void fail(Object msgId) {
        if (_fail == null) {
            _fail = new JSONObject();
            _fail.put("command", "fail");
        }

        _fail.put("id", msgId);
        querySubprocess(_fail);
    }
    
    private void handleMetrics(Map action) {
    	//get metrics
    	Object nameObj = action.get("name");
    	if ( !(nameObj instanceof String) ) {
    		throw new RuntimeException("Receive Metrics name is not String");
    	}
    	String name = (String) nameObj;
    	if (name == null || name.isEmpty()) {
    		throw new RuntimeException("Receive Metrics name is NULL");
    	}
    	if ( !_registeredShellMetrics.containsKey(name)) {
    		throw new RuntimeException("Receive Metrics name:" + name + " does not reigster.");
    	}
    	IShellMetric iMetric = _registeredShellMetrics.get(name);
    	
    	//get paramList
    	Object paramsObj = action.get("params");
        
    	Class<? extends IShellMetric> oriClass = iMetric.getClass();
    	Method method = null;
		try {
			method = oriClass.getMethod(IShellMetric.SHELL_METRICS_UPDATE_METHOD_NAME, new Class[]{Object.class});
		} catch (SecurityException e) {
			LOG.error("handleMetrics get method ["+name+"] SecurityException");
			throw new RuntimeException(e);
		} catch (NoSuchMethodException e) {
			LOG.error("handleMetrics get method ["+name+"] NoSuchMethodException");
			throw new RuntimeException(e);
		}
    	try {
			method.invoke(iMetric, paramsObj);
		} catch (IllegalArgumentException e) {
			LOG.error("handleMetrics invoke["+name+"] IllegalArgumentException");
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			LOG.error("handleMetrics invoke["+name+"] IllegalAccessException");
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			LOG.error("handleMetrics invoke["+name+"] InvocationTargetException");
			throw new RuntimeException(e);
		}
    }

    private void querySubprocess(Object query) {
        try {
            _process.writeMessage(query);

            while (true) {
                JSONObject action = _process.readMessage();
                String command = (String) action.get("command");
                if (command.equals("sync")) {
                    return;
                } else if (command.equals("log")) {
                    String msg = (String) action.get("msg");
                    LOG.info("Shell msg: " + msg);
                } else if (command.equals("emit")) {
                    String stream = (String) action.get("stream");
                    if (stream == null) stream = Utils.DEFAULT_STREAM_ID;
                    Long task = (Long) action.get("task");
                    List<Object> tuple = (List) action.get("tuple");
                    Object messageId = (Object) action.get("id");
                    if (task == null) {
                        List<Integer> outtasks = _collector.emit(stream, tuple, messageId);
                        Object need_task_ids = action.get("need_task_ids");
                        if (need_task_ids == null || ((Boolean) need_task_ids).booleanValue()) {
                            _process.writeMessage(outtasks);
                        }
                    } else {
                        _collector.emitDirect((int)task.longValue(), stream, tuple, messageId);
                    }
                } else if (command.equals("metrics")) {
                	handleMetrics(action);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    public <T extends IShellMetric> T registerMetric(String name, T metric) {
    	if ( _registeredShellMetrics.containsKey(name) ) {
    		throw new RuntimeException("The same metric name `" + name + "` was registered in ShellSpout twice." );
    	} else {
    		_registeredShellMetrics.put(name, metric);
    	}
    	return metric;
    }

    @Override
    public void activate() {
    }

    @Override
    public void deactivate() {
    }
}
