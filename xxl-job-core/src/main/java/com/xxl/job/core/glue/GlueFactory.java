package com.xxl.job.core.glue;

import com.xxl.job.core.glue.impl.GroovyGlueAdapter;
import com.xxl.job.core.glue.impl.SpringGlueFactory;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.tool.core.StringTool;


/**
 * glue factory, product class/object by name
 *
 * @author xuxueli 2016-1-2 20:02:27
 */
public class GlueFactory {


	private static GlueFactory glueFactory = new GlueFactory();

	protected GlueFactory(){
		if (isClassPresent("groovy.lang.GroovyClassLoader")) {
			groovyGlueAdapter = new GroovyGlueAdapter();
		}
	}


	public static GlueFactory getInstance(){
		return glueFactory;
	}

	/**
	 * refresh instance by type
	 *
	 * @param type		0-frameless, 1-spring;
	 */
	public static void refreshInstance(int type){
		if (type == 0) {
			glueFactory = new GlueFactory();
		} else if (type == 1) {
			glueFactory = new SpringGlueFactory();
		}
	}


	/**
	 * groovy class loader
	 */
	private static GroovyGlueAdapter groovyGlueAdapter = null;
	/**
	 * load new instance, prototype
	 *
	 * @param codeSource  code source
	 * @return IJobHandler
	 */
	public IJobHandler loadNewInstance(String codeSource) throws Exception{
		if (StringTool.isNotBlank(codeSource)&&groovyGlueAdapter!=null) {
			Class<?> clazz = groovyGlueAdapter.getCodeSourceClass(codeSource);
			if (clazz != null) {
				Object instance = clazz.newInstance();
                if (instance instanceof IJobHandler) {
                    this.injectService(instance);
                    return (IJobHandler) instance;
                } else {
                    throw new IllegalArgumentException(">>>>>>>>>>> xxl-glue, loadNewInstance error, "
                            + "cannot convert from instance[" + instance.getClass() + "] to IJobHandler");
                }
            }
		}
		throw new IllegalArgumentException(">>>>>>>>>>> xxl-glue, loadNewInstance error, instance is null");
	}

	/**
	 * inject service of bean field
	 *
	 * @param instance  instance
	 */
	public void injectService(Object instance) {
		// do something
	}

	private boolean isClassPresent(String className){
		try {
			Class.forName(className, false, Thread.currentThread().getContextClassLoader());
			return true;
		}catch (Exception e){
			return false;
		}
	}

}
