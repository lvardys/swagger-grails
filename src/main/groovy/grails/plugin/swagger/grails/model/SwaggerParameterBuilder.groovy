package grails.plugin.swagger.grails.model

import grails.plugin.swagger.grails.SwaggerBuilderHelper
import grails.web.mapping.UrlMapping
import groovy.util.logging.Slf4j
import javassist.CtClass
import javassist.CtMethod
import javassist.bytecode.LocalVariableAttribute
import org.apache.commons.lang3.ClassUtils
import org.grails.core.DefaultGrailsControllerClass

import java.lang.reflect.Method

@Slf4j
class SwaggerParameterBuilder implements SwaggerBuilderHelper {
    /**
     * Using the supplied class and action name all of the path/query/body params
     * are found and mashed together.
     * any of the path params.
     *
     * @param controllerClass {@link org.grails.core.DefaultGrailsControllerClass}
     * @param actionName {@link String}
     * @param urlMapping {@link grails.web.mapping.UrlMapping}
     * @return List of path/query/body level swaggerParameters
     */
    static List<SwaggerParameter> buildSwaggerParameters(DefaultGrailsControllerClass controllerClass, String actionName, UrlMapping urlMapping) {
        List<String> pathParams = buildPathParams(urlMapping)

        Method method = controllerClass.clazz.methods.findAll {
            it.name == actionName
        }.sort {
            -it.genericParameterTypes.size()
        }.head()

        if (method.parameterCount == 0)
            return []

        CtClass ctClass = buildCtClass(controllerClass.clazz.name)
        CtMethod ctMethod = ctClass.getDeclaredMethod(method.name)
        LocalVariableAttribute attribute = ctMethod.getMethodInfo().getCodeAttribute().getAttribute(LocalVariableAttribute.tag) as LocalVariableAttribute

        (1..method.parameterCount).collect { int index ->
            Class<?> type = method.parameters[index - 1].getType()
            boolean isPrimitiveOrString = (ClassUtils.isPrimitiveOrWrapper(type) || type == String.class)
            String fieldName = attribute.variableName(index)
            String paramType = "body"

            if (isPrimitiveOrString && pathParams.contains(fieldName))
                paramType = "path"
            else if (isPrimitiveOrString && !pathParams.contains(fieldName))
                paramType = "query"

            new SwaggerParameter(name: fieldName, dataType: type.name, paramType: paramType)
        }.inject([]) { list, param ->
            if (param.validate())
                list << param
            else
                logParameterValidationError(controllerClass.naturalName, actionName, param)

            list
        }.sort {
            it.name
        }
    }

    /**
     * Finds all url data tokens that match the given regex.
     * An index is then added to this list that is used to find the corresponding
     * property name from the constraints object that is on the url mapping.
     * <br><br>
     * These params are used to determine if a method parameter needs to be
     * flagged as path param instead of a query/body parameter.
     *
     * @param urlMapping {@link grails.web.mapping.UrlMapping}
     * @return List of params that are on the path
     */
    private static List<String> buildPathParams(UrlMapping urlMapping) {
        if (!urlMapping)
            return []

        urlMapping.urlData.tokens.findAll {
            it ==~ /\(\*\)/
        }.withIndex().collect { String token, int index ->
            urlMapping.constraints[index].propertyName
        }
    }

    /**
     * Prints out validation errors in a human readable format
     *
     * @param controller Short name of controller
     * @param param {@link SwaggerParameter}
     */
    private static void logParameterValidationError(String controller, String actionName, SwaggerParameter param) {
        String replacement = "of class [class ${SwaggerParameter.class.name}] "
        String errors = param.errors.allErrors.collect { error ->
            "\t${messageSource.getMessage(error, null).replace(replacement, "")}"
        }.join("\n")

        log.error("""
        |$param failed validation @ $controller.$actionName()
        |$errors
        """.stripMargin())
    }
}