package com.xxl.job.core.glue.impl;

import groovy.lang.GroovyClassLoader;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class GroovyGlueAdapter {
    private final GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
    private final ConcurrentMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    public Class<?> getCodeSourceClass(String codeSource){
        try {
            // md5
            byte[] md5 = MessageDigest.getInstance("MD5").digest(codeSource.getBytes());
            String md5Str = new BigInteger(1, md5).toString(16);

            Class<?> clazz = CLASS_CACHE.get(md5Str);
            if(clazz == null){
                clazz = groovyClassLoader.parseClass(codeSource);
                CLASS_CACHE.putIfAbsent(md5Str, clazz);
            }
            return clazz;
        } catch (Exception e) {
            return groovyClassLoader.parseClass(codeSource);
        }
    }
}
