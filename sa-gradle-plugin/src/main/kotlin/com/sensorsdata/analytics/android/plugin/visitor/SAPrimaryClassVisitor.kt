package com.sensorsdata.analytics.android.plugin.visitor

import com.sensorsdata.analytics.android.gradle.ClassInheritance
import com.sensorsdata.analytics.android.plugin.ClassNameAnalytics
import com.sensorsdata.analytics.android.plugin.configs.SAConfigHookHelper
import com.sensorsdata.analytics.android.plugin.fragment.FragmentHookHelper
import com.sensorsdata.analytics.android.plugin.js.AddJSAnnotationVisitor
import com.sensorsdata.analytics.android.plugin.js.SensorsAnalyticsWebViewMethodVisitor
import com.sensorsdata.analytics.android.plugin.manager.SAModule
import com.sensorsdata.analytics.android.plugin.manager.SAPluginManager
import com.sensorsdata.analytics.android.plugin.push.SensorsAnalyticsPushMethodVisitor
import com.sensorsdata.analytics.android.plugin.push.SensorsPushInjected
import com.sensorsdata.analytics.android.plugin.version.SensorsAnalyticsVersionFieldVisitor
import com.sensorsdata.analytics.android.plugin.viewclick.SensorsAutoTrackMethodVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class SAPrimaryClassVisitor(
    private val classVisitor: ClassVisitor,
    private val pluginManager: SAPluginManager,
    private val classInheritance: ClassInheritance
) : ClassVisitor(pluginManager.getASMVersion(), classVisitor) {

    private lateinit var classNameAnalytics: ClassNameAnalytics
    private var shouldReturnJSRAdapter = false
    private var isFoundOnNewIntent = false

    private val visitedFragMethods = mutableSetOf<String>()
    private val mLambdaMethodCells = mutableMapOf<String, SensorsAnalyticsMethodCell>()
    private val configHookHelper = SAConfigHookHelper()

    /**
     * 访问并处理类定义信息。
     *
     * @param version 类文件的版本号（如V1_5对应Java 5）
     * @param access 类的访问标志（如public、final等）
     * @param name 类的内部名称（如Lcom/example/MyClass;）
     * @param signature 泛型签名（可为空）
     * @param superName 父类的内部名称（可为空）
     * @param interfaces 实现的接口列表（可为空）
     * @return Unit 无返回值
     */
    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        classNameAnalytics = ClassNameAnalytics(name, superName, interfaces?.asList())
        shouldReturnJSRAdapter = version <= Opcodes.V1_5
        configHookHelper.initConfigCellInClass(name)
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        //校验版本相关的操作
        val fieldVisitor = super.visitField(access, name, descriptor, signature, value)
        if (classNameAnalytics.isSensorsDataAPI || classNameAnalytics.isSensorsDataVersion) {
            return SensorsAnalyticsVersionFieldVisitor(
                pluginManager.getASMVersion(),
                fieldVisitor, name, value, pluginManager.sdkVersionHelper, classNameAnalytics
            )
        }
        return fieldVisitor
    }

    /**
     * 重写.visitMethod方法，用于处理类中方法的字节码插桩逻辑。
     * @param access 方法的访问标志（如public、static等）
     * @param name 方法名称
     * @param descriptor 方法描述符（包含参数类型和返回类型）
     * @param signature 泛型签名（可为空）
     * @param exceptions 抛出的异常列表（可为空）
     * @return 经过装饰后的MethodVisitor实例，用于后续字节码操作
     */
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor? {
        //check whether need to delete this method. if the method is deleted,
        //a new method will be created at visitEnd()
        if (configHookHelper.isConfigsMethod(name, descriptor)) {
            return null
        }

        if (classNameAnalytics.superClass == "android/app/Activity"
            && name == "onNewIntent" && descriptor == "(Landroid/content/Intent;)V"
        ) {
            isFoundOnNewIntent = true
        }

        var methodVisitor = cv.visitMethod(access, name, descriptor, signature, exceptions)
        if (pluginManager.isModuleEnable(SAModule.WEB_VIEW)) {
            methodVisitor =
                SensorsAnalyticsWebViewMethodVisitor(
                    pluginManager.getASMVersion(),
                    methodVisitor,
                    access,
                    name,
                    descriptor!!,
                    classNameAnalytics,
                    classInheritance
                )
        }

        if (pluginManager.isModuleEnable(SAModule.PUSH)) {
            methodVisitor = SensorsAnalyticsPushMethodVisitor(
                pluginManager.getASMVersion(),
                methodVisitor,
                access,
                name,
                descriptor,
                classNameAnalytics.superClass
            )
        }

        if (classNameAnalytics.isAppWebViewInterface) {
            // add JavaScriptInterface
            methodVisitor =
                AddJSAnnotationVisitor(methodVisitor, access, name, descriptor, pluginManager)
        }

        if (pluginManager.isModuleEnable(SAModule.AUTOTRACK)) {
            methodVisitor = SensorsAutoTrackMethodVisitor(
                methodVisitor, access, name!!, descriptor!!, classNameAnalytics,
                visitedFragMethods, mLambdaMethodCells, pluginManager
            )
        }

        methodVisitor =
            UpdateSDKPluginVersionMV(
                pluginManager.getASMVersion(),
                methodVisitor,
                access,
                name,
                descriptor,
                classNameAnalytics
            )

        if (shouldReturnJSRAdapter) {
            return SensorsAnalyticsJSRAdapter(
                pluginManager.getASMVersion(),
                methodVisitor,
                access,
                name,
                descriptor,
                signature,
                exceptions
            )
        }

        return methodVisitor
    }

    override fun visitEnd() {
        super.visitEnd()

        //给 Activity 添加 onNewIntent，满足 push 业务需求
        if (pluginManager.isModuleEnable(SAModule.PUSH)
            && !isFoundOnNewIntent
            && classNameAnalytics.superClass == "android/app/Activity"
        ) {
            SensorsPushInjected.addOnNewIntent(classVisitor)
        }

        //为 Fragment 添加方法，满足生命周期定义
        if (pluginManager.isModuleEnable(SAModule.AUTOTRACK)) {
            FragmentHookHelper.hookFragment(
                classVisitor,
                classNameAnalytics.superClass,
                visitedFragMethods
            )
        }

        //添加需要置空的方法
        configHookHelper.disableIdentifierMethod(classVisitor)
    }

}