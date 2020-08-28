package com.bilibili.brouter.apt.internal

import com.bilibili.brouter.api.BootStrapMode
import com.bilibili.brouter.api.Launcher
import com.bilibili.brouter.api.ModuleActivator
import com.bilibili.brouter.api.ServiceCentral
import com.bilibili.brouter.api.internal.Registry
import com.bilibili.brouter.api.internal.module.DefaultModuleActivator
import com.bilibili.brouter.api.internal.module.ModuleContainer
import com.bilibili.brouter.api.internal.module.ModuleData
import com.bilibili.brouter.api.internal.module.ModuleTaskOptions
import com.bilibili.brouter.api.task.TaskContainer
import com.bilibili.brouter.api.task.ThreadMode
import com.bilibili.brouter.apt.toClassName
import com.bilibili.brouter.common.meta.*
import com.squareup.javapoet.*
import javax.annotation.processing.Filer
import javax.inject.Provider
import javax.lang.model.element.Modifier


private const val CENTRAL = "central"
private const val REGISTRY = "registry"
private const val TASKS = "tasks"

internal fun ModuleMeta.generateSource(filer: Filer) {
    JavaFile.builder(
        entranceClass.substring(0, entranceClass.lastIndexOf('.')),
        TypeSpec.classBuilder(entranceClass.substring(entranceClass.lastIndexOf('.') + 1))
            .superclass(ClassName.get(ModuleContainer::class.java))
            .addJavadoc("Generated by BRouter, don't edit it.\n")
            .addJavadoc("Module: $moduleName\n")
            .addJavadoc("Bootstrap mode: $bootstrapMode\n")
            .addMethod(
                MethodSpec.constructorBuilder()
                    .addCode(
                        CodeBlock.builder()
                            .add(
                                "super(new \$T(\$S, \$T.$bootstrapMode,",
                                ModuleData::class.java,
                                moduleName,
                                BootStrapMode::class.java
                            )
                            .apply {
                                onCreate.asModuleTaskOptions(this, true)
                                onPostCreate.asModuleTaskOptions(
                                    this,
                                    onCreate == null
                                )
                                add(" ")
                                attributes.appendTo(this)
                            }
                            .add("));\n")
                            .build()
                    )
                    .build()
            )
            .apply {
                if (desc.isNotEmpty()) {
                    desc.split("\n")
                        .forEachIndexed { i, it ->
                            if (i == 0) {
                                addJavadoc("Description: $it\n")
                            } else {
                                addJavadoc("             $it\n")
                            }
                        }
                }

                // createActivator
                if (activatorClass != DefaultModuleActivator::class.java.name) {
                    addMethod(
                        MethodSpec.methodBuilder(ModuleContainer::createActivator.name)
                            .addAnnotation(Override::class.java)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(ModuleActivator::class.java)
                            .addParameter(ClassName.get(ServiceCentral::class.java), CENTRAL)
                            .addCode(
                                CodeBlock.builder().apply {
                                    add("\$[return new \$T(", activatorClass.toClassName())
                                    onCreate?.let {
                                        it.constructorParams.appendInMultiline(this) {
                                            it.appendTo(this)
                                        }
                                    }
                                    add("\$]);\n")
                                }.build()
                            )
                            .build()
                    )
                }

                // onRegister
                if (routes.isNotEmpty() || services.isNotEmpty() || tasks.isNotEmpty()) {
                    addMethod(
                        MethodSpec.methodBuilder(ModuleContainer::onRegister.name)
                            .addAnnotation(Override::class.java)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(TypeName.VOID)
                            .addParameter(ClassName.get(Registry::class.java), REGISTRY)
                            .addParameter(ClassName.get(TaskContainer::class.java), TASKS)
                            .addCode(
                                CodeBlock.builder().apply {
                                    if (services.isNotEmpty()) {
                                        add(
                                            "\$T $CENTRAL = $REGISTRY.deferred();\n",
                                            ServiceCentral::class.java
                                        )
                                        services.forEach {
                                            it.appendTo(this)
                                        }
                                        add("\n")
                                    }

                                    if (routes.isNotEmpty()) {
                                        routes.forEach {
                                            it.appendTo(this)
                                        }
                                        add("\n")
                                    }

                                    tasks.forEach {
                                        it.appendTo(this)
                                    }

                                }.build()
                            )
                            .build()
                    )
                }
            }
            .build()
    ).indent("    ").build().writeTo(filer)
}

private fun TaskMeta?.asModuleTaskOptions(
    builder: CodeBlock.Builder,
    includeConstructorDependencies: Boolean
) {
    if (this != null) {
        builder.add(
            "\$[ new \$T(\n\$S, \n${priority}, \n\$T.${threadMode}",
            ModuleTaskOptions::class.java,
            taskName,
            ThreadMode::class.java
        )
        val allDependencies =
            if (includeConstructorDependencies) taskDependencies + constructorParams else taskDependencies
        if (allDependencies.isNotEmpty()) {
            builder.add(",")
            appendDependencies(allDependencies, builder)
        }
        builder.add("\$]),")
    } else {
        builder.add(" null,")
    }
}

private fun TaskMeta.appendTo(builder: CodeBlock.Builder) {
    producedServices.forEach {
        it.serviceTypes.forEach { serviceType ->
            builder.add(
                "$REGISTRY.registerTaskOutputService(\$T.class, \$S, this, \$S);\n",
                serviceType.toClassName(),
                it.name,
                taskName
            )
        }
    }

    builder.add("tasks.register(\$S, (builder) -> {\n", taskName)
        .indent()
        .apply {
            add("builder.threadMode(\$T.${threadMode})\n", ThreadMode::class.java)
                .indent().indent()
                .add(".priority(${priority})\n")

            val allDependencies = taskDependencies + constructorParams
            if (allDependencies.isNotEmpty()) {
                add(".dependsOn(").indent().indent()
                appendDependencies(allDependencies, this)
                unindent().unindent().add(")\n")
            }
            add(".doLast((task) -> {\n")
                .indent()
                .add("\$[\$T action = new \$T(", className.toClassName(), className.toClassName())
            constructorParams.appendInMultiline(this) {
                it.appendTo(this, "task.getServices()")
            }

            add("\$]);\n")
            add("action.execute(task);\n")


            producedServices.forEachIndexed { i, it ->
                val varName = "var$i"
                add("\$T $varName = action.${it.fieldOrMethodName}", it.returnType.toClassName())
                if (!it.isField) {
                    add("()")
                }
                add(";\n")
                it.serviceTypes.forEach { serviceType ->
                    add(
                        "task.getOutputs().output(\$T.class, \$S, $varName);\n",
                        serviceType.toClassName(),
                        it.name
                    );
                }
            }



            add("return null;\n").unindent().add("});\n")
            unindent().unindent()
        }
        .add("return null;\n")
        .unindent()
        .add("});\n\n")
}

private fun ServiceMeta.appendTo(builder: CodeBlock.Builder) {
    builder.add("{\n")
        .indent()

    // desc
    if (desc.isNotEmpty()) {
        desc.split("\n")
            .forEach {
                builder.add("// $it\n")
            }
    }
    // generate provider
    builder.add(
        "\$[\$T p = ",
        ParameterizedTypeName.get(
            ClassName.get(Provider::class.java),
            WildcardTypeName.subtypeOf(returnType.toClassName())
        )
    )
    if (singleton) {
        builder.add("\$T.singletonProvider(", _BuiltInKt)
    }
    if (sourceMethodName == "<init>") {
        builder.add("() -> new \$T(", sourceClassName.toClassName())
    } else {
        builder.add("() -> \$T.$sourceMethodName(", sourceClassName.toClassName())
    }
    methodParams.appendInMultiline(builder) {
        it.appendTo(builder)
    }
    builder.add("\$])")
    if (singleton) {
        builder.add(")")
    }
    builder.add(";\n")

    // dependencies
    val allDependencies = taskDependencies + methodParams
    if (allDependencies.isEmpty()) {
        builder.add("Object[] dep = \$T.emptyAnyArray();\n", _BuiltInKt)
    } else {
        builder.add("\$[Object[] dep = new Object[]{", _BuiltInKt)
        appendDependencies(allDependencies, builder)
        builder.add("\$]};\n")
    }

    // register
    serviceTypes.forEach {
        builder.add(
            "$REGISTRY.registerProviderService(\$T.class, \$S, p, this, dep);\n",
            it.toClassName(),
            serviceName
        )
    }
    builder.unindent()
        .add("}\n")
}

private fun appendDependencies(
    dependencies: List<Any>,
    builder: CodeBlock.Builder
) {
    dependencies.appendInMultiline(builder) {
        if (it is String) {
            builder.add("\"$it\"")
        } else {
            it as ServiceDependency
            builder.add(
                "new \$T(\$T.class, \$S, ${it.optional})",
                com.bilibili.brouter.api.task.ServiceDependency::class.java,
                it.className.toClassName(),
                it.serviceName
            )
        }
    }
}

internal val _BuiltInKt = ClassName.get("com.bilibili.brouter.api.internal", "BuiltInKt")
private val _TuplesKt = ClassName.get("kotlin", "TuplesKt")
private fun RouteMeta.appendTo(builder: CodeBlock.Builder) {
    if (desc.isNotEmpty()) {
        desc.split("\n")
            .forEach {
                builder.add("// $it\n")
            }
    }
    builder.add("$REGISTRY.registerRoutes(\n")
        .indent().indent()
        .apply {
            // routes
            add("\$T.routesBean(\n", _BuiltInKt).indent().indent()
            // name
            add("\$S,\n", routeName)

            // new String[]
            add("new \$T[]{", String::class.java)
                .indent().indent()
                .apply {
                    routeRules.appendInMultiline(this) {
                        add("\$S", it)
                    }
                }
                .unindent().unindent()
                .add("}, ")
            // routeType
            add("\$S,\n", routeType)

            // attributes
            attributes.appendTo(this)
            this.add(",\n")

            // interceptors
            if (interceptors.isEmpty()) {
                add("\$T.emptyArrayProvider(),\n", _BuiltInKt)
            } else {
                add(
                    "\$[() -> new \$T[]{",
                    Class::class.java
                )
                interceptors.appendInMultiline(this) {
                    add("\$T.class", it.toClassName())
                }
                add("\$]},\n")
            }
            // launcher
            if (launcher == Launcher::class.java.name) {
                add("\$T.stubLauncherProvider(),\n", _BuiltInKt)
            } else {
                add("() -> \$T.class,\n", launcher.toClassName())
            }
            // class
            add("() -> \$T.class,\n", className.toClassName())
            add("this\n")

        }
        .unindent().unindent()
        .add(")\n")
        .unindent().unindent()
        .add(");\n")
}

private fun <T> List<T>.appendInMultiline(builder: CodeBlock.Builder, action: (T) -> Unit) {
    if (isNotEmpty()) {
        builder.add("\n")
    }
    forEachIndexed { i, it ->
        action(it)
        if (i == lastIndex) {
            builder.add("\n")
        } else {
            builder.add(",\n")
        }
    }
}

fun ServiceDependency.appendTo(builder: CodeBlock.Builder, serviceObjName: String = CENTRAL) {
    builder.add(
        "$serviceObjName.${when (type) {
            DepType.VALUE -> "getService"
            DepType.PROVIDER -> "getProvider"
            DepType.WILDCARD_PROVIDER -> "getProviderWildcard"
        }
        }(\$T.class, \$S)",
        className.toClassName(), serviceName
    )
}

private fun List<AttributeMeta>.appendTo(builder: CodeBlock.Builder) {
    if (isEmpty()) {
        builder.add("\$T.emptyAttributesArray()", _BuiltInKt)
    } else {
        builder.add(
            "\$[new \$T[]{",
            Pair::class.java
        )
        this.appendInMultiline(builder) {
            builder.add("\$T.to(\$S, \$S)", _TuplesKt, it.name, it.value)
        }
        builder.add("\$]}")
    }
}