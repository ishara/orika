/*
 * Orika - simpler, better and faster Java bean mapping
 * 
 * Copyright (C) 2011 Orika authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ma.glasnost.orika.impl.generator;

import static ma.glasnost.orika.impl.Specifications.*;

import java.util.Collection;
import java.util.Set;

import javassist.CannotCompileException;
import ma.glasnost.orika.Converter;
import ma.glasnost.orika.MapperFactory;
import ma.glasnost.orika.MappingContext;
import ma.glasnost.orika.MappingException;
import ma.glasnost.orika.converter.ConverterFactory;
import ma.glasnost.orika.impl.GeneratedMapperBase;
import ma.glasnost.orika.metadata.ClassMap;
import ma.glasnost.orika.metadata.FieldMap;
import ma.glasnost.orika.metadata.MapperKey;
import ma.glasnost.orika.metadata.Property;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MapperGenerator {
    
    private static Logger LOG = LoggerFactory.getLogger(MapperGenerator.class);
    
    private final MapperFactory mapperFactory;
    private final CompilerStrategy compilerStrategy;
    private final UsedTypesContext usedTypes;
    
    public MapperGenerator(MapperFactory mapperFactory, CompilerStrategy compilerStrategy) {
        this.mapperFactory = mapperFactory;
        this.compilerStrategy = compilerStrategy;
        this.usedTypes = new UsedTypesContext();
    }
    
    public GeneratedMapperBase build(ClassMap<?, ?> classMap) {
        
        try {
            compilerStrategy.assureTypeIsAccessible(classMap.getAType().getRawType());
            compilerStrategy.assureTypeIsAccessible(classMap.getBType().getRawType());
            
            final GeneratedSourceCode mapperCode = new GeneratedSourceCode(classMap.getMapperClassName(), GeneratedMapperBase.class,
                    compilerStrategy);
            
            addMapMethod(mapperCode, true, classMap);
            addMapMethod(mapperCode, false, classMap);
            
            GeneratedMapperBase instance = mapperCode.getInstance();
            instance.setAType(classMap.getAType());
            instance.setBType(classMap.getBType());
            instance.setUsedTypes(usedTypes.getUsedTypesArray());
            
            return instance;
            
        } catch (final Exception e) {
            throw new MappingException(e);
        }
    }
    
    private void addMapMethod(GeneratedSourceCode context, boolean aToB, ClassMap<?, ?> classMap) throws CannotCompileException {
        
        final CodeSourceBuilder out = new CodeSourceBuilder(2, usedTypes);
        final String mapMethod = "map" + (aToB ? "AtoB" : "BtoA");
        out.append("\tpublic void ")
                .append(mapMethod)
                .append("(java.lang.Object a, java.lang.Object b, %s mappingContext) {\n\n", MappingContext.class.getCanonicalName());
        
        Class<?> sourceClass, destinationClass;
        if (aToB) {
            sourceClass = classMap.getAType().getRawType();
            destinationClass = classMap.getBType().getRawType();
        } else {
            sourceClass = classMap.getBType().getRawType();
            destinationClass = classMap.getAType().getRawType();
        }
        out.assertType("a", sourceClass);
        out.assertType("b", destinationClass);
        out.append("\n\t\tsuper.").append(mapMethod).append("(a,b, mappingContext);\n\n");
        out.append("\t\t" + sourceClass.getCanonicalName())
                .append(" source = \n\t\t\t\t (")
                .append(sourceClass.getCanonicalName())
                .append(") a; \n");
        out.append("\t\t" + destinationClass.getCanonicalName())
                .append(" destination = \n\t\t\t\t (")
                .append(destinationClass.getCanonicalName())
                .append(") b; \n\n");
        
        for (FieldMap fieldMap : classMap.getFieldsMapping()) {
            if (isAlreadyExistsInUsedMappers(fieldMap, classMap)) {
                continue;
            }
            if (!aToB) {
                fieldMap = fieldMap.flip();
            }
            if (!fieldMap.isIgnored()) {
                try {
                    generateFieldMapCode(out, fieldMap, destinationClass);
                } catch (final Exception e) {
                    throw new MappingException(e);
                }
            }
        }
        out.append("\n\t\tif(customMapper != null) { \n\t\t\t customMapper.")
                .append(mapMethod)
                .append("(source, destination, mappingContext);\n\t\t}");
        
        out.append("\n\t}");
        
        context.addMethod(out.toString());
    }
    
    private boolean isAlreadyExistsInUsedMappers(FieldMap fieldMap, ClassMap<?, ?> classMap) {
        
        Set<ClassMap<Object, Object>> usedClassMapSet = mapperFactory.lookupUsedClassMap(new MapperKey(classMap.getAType(),
                classMap.getBType()));
        
        boolean exists = false;
        
        for (ClassMap<Object, Object> usedClassMap : usedClassMapSet) {
            if (usedClassMap.getFieldsMapping().contains(fieldMap))
                exists = true;
            break;
        }
        
        return exists;
    }
    
    private void generateFieldMapCode(CodeSourceBuilder code, FieldMap fieldMap, Class<?> destinationClass) throws Exception {
        final Property sp = fieldMap.getSource(), dp = fieldMap.getDestination();
        
        if (sp.getGetter() == null || ((dp.getSetter() == null) && !Collection.class.isAssignableFrom(dp.getRawType()))) {
            return;
        }
        
        // Make sure the source and destination types are accessible to the
        // builder
        
        compilerStrategy.assureTypeIsAccessible(sp.getRawType());
        compilerStrategy.assureTypeIsAccessible(dp.getRawType());
        
        try {
            
            //
            // Ensure that there we will not cause a NPE
            //
            if (sp.hasPath()) {
                code.avoidSourceNPE(sp).then();
            }
            
            if (dp.hasPath()) {
                code.ifDestinationNull(dp);
            }
            
            // Generate mapping code for every case
            
            Converter<Object, Object> converter = getConverter(fieldMap);
            if (converter != null) {
                code.convert(dp, sp, fieldMap.getConverterId());
            } else if (fieldMap.is(toAnEnumeration())) {
                code.setToEnumeration(dp, sp);
            } else if (fieldMap.is(immutable())) {
                code.set(dp, sp);
            } else if (fieldMap.is(anArray())) {
                code.setArray(dp, sp);
            } else if (fieldMap.is(aCollection())) {
                code.setCollection(dp, sp, fieldMap.getInverse(), destinationClass);
            } else if (fieldMap.is(aWrapperToPrimitive())) {
                code.setPrimitive(dp, sp);
            } else if (fieldMap.is(aPrimitiveToWrapper())) {
                code.setWrapper(dp, sp);
            } else if (fieldMap.is(aConversionFromString())) {
                code.setFromStringConversion(dp, sp);
            } else if (fieldMap.is(aConversionToString())) {
                code.setToStringConversion(dp, sp);
            } else {
                /**/
                
                if (sp.isPrimitive() || dp.isPrimitive())
                code.newLine().append("/* Ignore field map : %s:%s -> %s:%s */", sp.getExpression(), sp.getType().getSimpleName(), dp.getExpression(), dp.getType().getSimpleName());
                
                else {
                    code.setObject(dp, sp, fieldMap.getInverse());
                }
            }
            
            // Close up, and set null to destination
            if (sp.hasPath()) {
                code.end();
            }
            
        } catch (final Exception e) {
            if (fieldMap.isConfigured()) {
                throw e;
                // elsewise ignore
            }
        }
    }
    
    private Converter<Object, Object> getConverter(FieldMap fieldMap) {
        Converter<Object, Object> converter = null;
        ConverterFactory converterFactory = mapperFactory.getConverterFactory();
        if (fieldMap.getConverterId() != null) {
            converter = converterFactory.getConverter(fieldMap.getConverterId());
        } else {
            converter = converterFactory.getConverter(fieldMap.getSource().getType(),
                     fieldMap.getDestination().getType());
        }
        return converter;
    }
    
}