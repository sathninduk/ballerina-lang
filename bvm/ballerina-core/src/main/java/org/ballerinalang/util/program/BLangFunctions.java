/*
*  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/
package org.ballerinalang.util.program;

import org.ballerinalang.bre.bvm.AsyncTimer;
import org.ballerinalang.bre.bvm.BLangScheduler;
import org.ballerinalang.bre.bvm.BLangVMErrors;
import org.ballerinalang.bre.bvm.CallableUnitCallback;
import org.ballerinalang.bre.bvm.CallbackedInvocableWorkerResponseContext;
import org.ballerinalang.bre.bvm.ForkJoinTimeoutCallback;
import org.ballerinalang.bre.bvm.ForkJoinWorkerResponseContext;
import org.ballerinalang.bre.bvm.InvocableWorkerResponseContext;
import org.ballerinalang.bre.bvm.InitWorkerResponseContext;
import org.ballerinalang.bre.bvm.WorkerData;
import org.ballerinalang.bre.bvm.WorkerExecutionContext;
import org.ballerinalang.bre.bvm.WorkerResponseContext;
import org.ballerinalang.model.values.BStruct;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.util.codegen.CallableUnitInfo;
import org.ballerinalang.util.codegen.ForkjoinInfo;
import org.ballerinalang.util.codegen.FunctionInfo;
import org.ballerinalang.util.codegen.PackageInfo;
import org.ballerinalang.util.codegen.ProgramFile;
import org.ballerinalang.util.codegen.WorkerInfo;
import org.ballerinalang.util.codegen.attributes.CodeAttributeInfo;
import org.ballerinalang.util.exceptions.BLangRuntimeException;
import org.wso2.ballerinalang.util.Lists;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * This class contains helper methods to invoke Ballerina functions.
 *
 * @since 0.8.0
 */
public class BLangFunctions {
    private static final String JOIN_TYPE_SOME = "some";

    private BLangFunctions() { }

    /**
     * This method calls a program callable, considering it as an entry point callable. Which means, this callable will 
     * be invoked as the first callable in a program, and after it is called, all the cleanup will be done for it to 
     * exit from the program. That is, this callable will wait for the response to be fully available, and it will wait
     * till all the workers in the system to finish executing.
     * @param bLangProgram the program file
     * @param packageName the package the callable is residing
     * @param callableName the callable name
     * @param args the callable arguments
     * @param parentCtx the parent worker execution context
     * @return
     */
    public static BValue[] invokeEntrypointCallable(ProgramFile bLangProgram, String packageName, String callableName,
                                     BValue[] args) {
        PackageInfo packageInfo = bLangProgram.getPackageInfo(packageName);
        FunctionInfo functionInfo = packageInfo.getFunctionInfo(callableName);
        if (functionInfo == null) {
            throw new RuntimeException("Function '" + callableName + "' is not defined");
        }
        return invokeEntrypointCallable(bLangProgram, packageInfo, functionInfo, args);
    }
    
    public static BValue[] invokeEntrypointCallable(ProgramFile programFile, PackageInfo packageInfo,
            FunctionInfo functionInfo, BValue[] args) {
        WorkerExecutionContext parentCtx = new WorkerExecutionContext(programFile);
        if (functionInfo.getParamTypes().length != args.length) {
            throw new RuntimeException("Size of input argument arrays is not equal to size of function parameters");
        }
        invokePackageInitFunction(packageInfo.getInitFunctionInfo(), parentCtx);
        BValue[] result = invokeCallable(functionInfo, parentCtx, args);
        BLangScheduler.waitForWorkerCompletion();
        return result;
    }
    
    public static void invokeCallable(CallableUnitInfo callableUnitInfo, WorkerExecutionContext parentCtx) {
        invokeCallable(callableUnitInfo, parentCtx, new int[0], new int[0], false);
    }
    
    public static BValue[] invokeCallable(CallableUnitInfo callableUnitInfo, BValue[] args) {
        return invokeCallable(callableUnitInfo, new WorkerExecutionContext(callableUnitInfo.getPackageInfo()
                .getProgramFile()), args);
    }
    
    public static BValue[] invokeCallable(CallableUnitInfo callableUnitInfo, WorkerExecutionContext parentCtx, 
            BValue[] args) {
        int[][] regs = BLangVMUtils.populateArgAndReturnData(parentCtx, callableUnitInfo, args);
        invokeCallable(callableUnitInfo, parentCtx, regs[0], regs[1], true);
        return BLangVMUtils.populateReturnData(parentCtx, callableUnitInfo, regs[1]);
    }
    
    public static void invokeCallable(CallableUnitInfo callableUnitInfo, WorkerExecutionContext parentCtx,
            BValue[] args, CallableUnitCallback responseCallback) {
        int[][] regs = BLangVMUtils.populateArgAndReturnData(parentCtx, callableUnitInfo, args);
        invokeCallable(callableUnitInfo, parentCtx, regs[0], regs[1], responseCallback);
    }

    /**
     * This method does not short circuit the execution of the first worker to execute in the
     * same calling thread, but rather executes all the workers in their own separate threads.
     * This is specifically useful in executing service resources, where the calling transport
     * threads shouldn't be blocked, but rather the worker threads should be used.
     */
    public static void invokeCallable(CallableUnitInfo callableUnitInfo,
            WorkerExecutionContext parentCtx, int[] argRegs, int[] retRegs,
            CallableUnitCallback responseCallback) {
        WorkerSet workerSet = listWorkers(callableUnitInfo);
        InvocableWorkerResponseContext respCtx = new CallbackedInvocableWorkerResponseContext(
                callableUnitInfo.getRetParamTypes(), workerSet.generalWorkers.length, false, responseCallback);
        respCtx.updateTargetContextInfo(parentCtx, retRegs);
        WorkerDataIndex wdi = callableUnitInfo.retWorkerIndex;
        Map<String, Object> globalProps = parentCtx.globalProps;
        BLangScheduler.switchToWaitForResponse(parentCtx);

        /* execute the init worker and extract the local variables created by it */
        WorkerData initWorkerLocalData = null;
        CodeAttributeInfo initWorkerCAI = null;
        if (workerSet.initWorker != null) {
            initWorkerLocalData = executeInitWorker(parentCtx, argRegs, callableUnitInfo, workerSet.initWorker,
                    wdi, globalProps);
            if (initWorkerLocalData == null) {
                return;
            }
            initWorkerCAI = workerSet.initWorker.getCodeAttributeInfo();
        }

        for (int i = 0; i < workerSet.generalWorkers.length; i++) {
            executeWorker(respCtx, parentCtx, argRegs, callableUnitInfo, workerSet.generalWorkers[i],
                    wdi, globalProps, initWorkerLocalData, initWorkerCAI, false);
        }
    }

    public static WorkerExecutionContext invokeCallable(CallableUnitInfo callableUnitInfo,
            WorkerExecutionContext parentCtx, int[] argRegs, int[] retRegs, boolean waitForResponse) {
        WorkerSet workerSet = listWorkers(callableUnitInfo);
        InvocableWorkerResponseContext respCtx = new InvocableWorkerResponseContext(
                callableUnitInfo.getRetParamTypes(),
                workerSet.generalWorkers.length, waitForResponse);
        respCtx.updateTargetContextInfo(parentCtx, retRegs);
        WorkerDataIndex wdi = callableUnitInfo.retWorkerIndex;
        Map<String, Object> globalProps = parentCtx.globalProps;
        BLangScheduler.switchToWaitForResponse(parentCtx);

        /* execute the init worker and extract the local variables created by it */
        WorkerData initWorkerLocalData = null;
        CodeAttributeInfo initWorkerCAI = null;
        if (workerSet.initWorker != null) {
            initWorkerLocalData = executeInitWorker(parentCtx, argRegs, callableUnitInfo, workerSet.initWorker,
                    wdi, globalProps);
            if (initWorkerLocalData == null) {
                handleError(parentCtx);
                return null;
            }
            initWorkerCAI = workerSet.initWorker.getCodeAttributeInfo();
        }

        for (int i = 1; i < workerSet.generalWorkers.length; i++) {
            executeWorker(respCtx, parentCtx, argRegs, callableUnitInfo, workerSet.generalWorkers[i],
                    wdi, globalProps, initWorkerLocalData, initWorkerCAI, false);
        }
        WorkerExecutionContext runInCallerCtx = executeWorker(respCtx, parentCtx, argRegs, callableUnitInfo, 
                workerSet.generalWorkers[0], wdi, globalProps, initWorkerLocalData, initWorkerCAI, true);
        if (waitForResponse) {
            BLangScheduler.executeNow(runInCallerCtx);
            respCtx.waitForResponse();
            // An error in the context at this point means an unhandled runtime error has propagated
            // all the way up to the entry point. Hence throw a {@link BLangRuntimeException} and
            // terminate the execution.
            BStruct error = parentCtx.getError();
            if (error != null) {
                handleError(parentCtx);
            }
        }
        return runInCallerCtx;
    }
    
    private static void handleError(WorkerExecutionContext ctx) {
        throw new BLangRuntimeException("error: " + BLangVMErrors.getPrintableStackTrace(ctx.getError()));
    }
    
    private static WorkerExecutionContext executeWorker(WorkerResponseContext respCtx, 
            WorkerExecutionContext parentCtx, int[] argRegs, CallableUnitInfo callableUnitInfo, 
            WorkerInfo workerInfo, WorkerDataIndex wdi, Map<String, Object> globalProps,
            WorkerData initWorkerLocalData, CodeAttributeInfo initWorkerCAI, boolean runInCaller) {
        WorkerData workerLocal = BLangVMUtils.createWorkerDataForLocal(workerInfo, parentCtx, argRegs,
                callableUnitInfo.getParamTypes());
        if (initWorkerLocalData != null) {
            BLangVMUtils.mergeInitWorkertData(initWorkerLocalData, workerLocal, initWorkerCAI);
        }
        WorkerData workerResult = BLangVMUtils.createWorkerData(wdi);
        WorkerExecutionContext ctx = new WorkerExecutionContext(parentCtx, respCtx, callableUnitInfo, workerInfo,
                workerLocal, workerResult, wdi.retRegs, globalProps, runInCaller);
        return BLangScheduler.schedule(ctx);
    }
    
    private static WorkerData executeInitWorker(WorkerExecutionContext parentCtx, int[] argRegs,
            CallableUnitInfo callableUnitInfo, WorkerInfo workerInfo, WorkerDataIndex wdi,
            Map<String, Object> globalProps) {
        InitWorkerResponseContext respCtx = new InitWorkerResponseContext(parentCtx);
        WorkerExecutionContext ctx = executeWorker(respCtx, parentCtx, argRegs, callableUnitInfo,
                workerInfo, wdi, globalProps, null, null, true);        
        BLangScheduler.executeNow(ctx);
        WorkerData workerLocal = ctx.workerLocal;
        if (respCtx.isErrored()) {
            return null;
        } else {
            return workerLocal;
        }
    }

    private static WorkerSet listWorkers(CallableUnitInfo callableUnitInfo) {
        WorkerSet result = new WorkerSet();
        result.generalWorkers = callableUnitInfo.getWorkerInfoEntries();
        if (result.generalWorkers.length == 0) {
            result.generalWorkers = new WorkerInfo[] { callableUnitInfo.getDefaultWorkerInfo() };
        } else {
            result.initWorker = callableUnitInfo.getDefaultWorkerInfo();
        }
        return result;
    }
    
    public static void invokePackageInitFunction(FunctionInfo initFuncInfo, WorkerExecutionContext context) {
        invokeCallable(initFuncInfo, context, new int[0], new int[0], true);
        if (context.getError() != null) {
            String stackTraceStr = BLangVMErrors.getPrintableStackTrace(context.getError());
            throw new BLangRuntimeException("error: " + stackTraceStr);
        }
        ProgramFile programFile = initFuncInfo.getPackageInfo().getProgramFile();
        if (programFile.getUnresolvedAnnAttrValues() == null) {
            return;
        }
        BLangVMUtils.processUnresolvedAnnAttrValues(programFile);
        programFile.setUnresolvedAnnAttrValues(null);
    }

    public static void invokePackageInitFunction(FunctionInfo initFuncInfo) {
        WorkerExecutionContext context = new WorkerExecutionContext(initFuncInfo.getPackageInfo().getProgramFile());
        invokePackageInitFunction(initFuncInfo, context);
    }

    public static void invokeServiceInitFunction(FunctionInfo initFuncInfo) {
        WorkerExecutionContext context = new WorkerExecutionContext(initFuncInfo.getPackageInfo().getProgramFile());
        invokeCallable(initFuncInfo, context, new int[0], new int[0], true);
        if (context.getError() != null) {
            String stackTraceStr = BLangVMErrors.getPrintableStackTrace(context.getError());
            throw new BLangRuntimeException("error: " + stackTraceStr);
        }
    }

    public static WorkerExecutionContext invokeForkJoin(WorkerExecutionContext parentCtx, ForkjoinInfo forkjoinInfo,
            int joinTargetIp, int joinVarReg, int timeoutRegIndex, int timeoutTargetIp, int timeoutVarReg) {
        WorkerInfo[] workerInfos = forkjoinInfo.getWorkerInfos();

        Set<String> joinWorkerNames = new LinkedHashSet<>(Lists.of(forkjoinInfo.getJoinWorkerNames()));
        if (joinWorkerNames.isEmpty()) {
            /* if no join workers are specified, that means, all should be considered */
            joinWorkerNames.addAll(forkjoinInfo.getWorkerInfoMap().keySet());
        }

        Map<String, String> channels = getChannels(forkjoinInfo);

        int reqJoinCount;
        if (forkjoinInfo.getJoinType().equalsIgnoreCase(JOIN_TYPE_SOME)) {
            reqJoinCount = forkjoinInfo.getWorkerCount();
        } else {
            reqJoinCount = joinWorkerNames.size();
        }

        InvocableWorkerResponseContext respCtx = new ForkJoinWorkerResponseContext(parentCtx, joinTargetIp, joinVarReg,
                timeoutTargetIp, timeoutVarReg, workerInfos.length, reqJoinCount, joinWorkerNames, channels);
        if (forkjoinInfo.isTimeoutAvailable()) {
            long timeout = parentCtx.workerLocal.longRegs[timeoutRegIndex];
            //fork join timeout is in seconds, hence converting to milliseconds
            AsyncTimer.schedule(new ForkJoinTimeoutCallback(respCtx), timeout * 1000);
        }
        Map<String, Object> globalProps = parentCtx.globalProps;
        BLangScheduler.switchToWaitForResponse(parentCtx);
        for (int i = 1; i < workerInfos.length; i++) {
            executeWorker(respCtx, parentCtx, forkjoinInfo.getArgRegs(), workerInfos[i], globalProps, false);
        }

        return executeWorker(respCtx, parentCtx, forkjoinInfo.getArgRegs(),
                workerInfos[0], globalProps, true);
    }

    private static WorkerExecutionContext executeWorker(WorkerResponseContext respCtx,
            WorkerExecutionContext parentCtx, int[] argRegs, WorkerInfo workerInfo,
            Map<String, Object> globalProps, boolean runInCaller) {
        WorkerData workerLocal = BLangVMUtils.createWorkerDataForLocal(workerInfo, parentCtx, argRegs);
        WorkerExecutionContext ctx = new WorkerExecutionContext(parentCtx, respCtx, parentCtx.callableUnitInfo,
                workerInfo, workerLocal, globalProps, runInCaller);
        return BLangScheduler.schedule(ctx);
    }

    private static Map<String, String> getChannels(ForkjoinInfo forkjoinInfo) {
        Map<String, String> channels = new HashMap<>();
        forkjoinInfo.getWorkerInfoMap().forEach((k, v) -> channels.put(k, v.getWorkerDataChannelInfoForForkJoin()
                != null ? v.getWorkerDataChannelInfoForForkJoin().getChannelName() : null));
        return channels;
    }

    /**
     * This represents a worker set with different execution roles.
     */
    private static class WorkerSet {

        public WorkerInfo initWorker;

        public WorkerInfo[] generalWorkers;

    }

}
