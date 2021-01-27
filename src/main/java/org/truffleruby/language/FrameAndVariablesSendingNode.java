/*
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language;

import com.oracle.truffle.api.frame.Frame;
import org.truffleruby.SuppressFBWarnings;
import org.truffleruby.core.binding.RubyBinding;
import org.truffleruby.core.kernel.TruffleKernelNodes.GetSpecialVariableStorage;
import org.truffleruby.language.arguments.ReadCallerFrameNode;
import org.truffleruby.language.arguments.ReadCallerVariablesNode;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.utilities.AlwaysValidAssumption;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.truffleruby.language.FrameOrVariablesReadingNode.Reads;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.threadlocal.SpecialVariableStorage;

/** Some Ruby methods need access to the caller frame (the frame active when the method call was made) or to the storage
 * of special variables within that frame: see usages of {@link ReadCallerFrameNode} and {@link ReadCallerVariablesNode}
 * . This is notably used to get hold of instances of {@link DeclarationContext} and {@link RubyBinding} and methods
 * which need to access the last regexp match or the last io line.
 *
 * <p>
 * This means that when making a method call, we might need to pass down its {@link Frame} or
 * {@link SpecialVariableStorage} active when the method call was made.
 *
 * <p>
 * When retrieving the frame or special variable storage in a method called through the Ruby {@code #send} method, we
 * must not retrieve the frame of the actual call (made by {@code #send}) but the frame of the {@code #send} call
 * itself.
 *
 * <p>
 * Materializing a frame is expensive, and the point of this parent node is to only materialize the frame when we know
 * for sure it has been requested by the callee. It is also possible to walk the stack to retrieve the frame to
 * materialize - but this is even slower and causes a deoptimization in the callee every time we walk the stack.
 *
 * <p>
 * This class works in tandem with {@link ReadCallerFrameNode} for this purpose. At first, we don't send down the frame.
 * If the callee needs it, it will de-optimize and walk the stack to retrieve it (slow). It will also call
 * {@link #startSendingOwnFrame()}}, so that the next time the method is called, the frame will be passed down and the
 * method does not need further de-optimizations. (Note in the case of {@code #send} calls, we need to recursively call
 * {@link ReadCallerFrameNode} to get the parent frame!) {@link ReadCallerVariablesNode} is used similarly to access
 * special variable storage, but for child nodes that only require access to this storage ensures they receive an object
 * that will not require node splitting to be accessed efficiently.
 *
 * <p>
 * This class is the sole consumer of {@link RubyRootNode#getNeedsCallerAssumption()}, which is used to optimize
 * {@link #getFrameOrStorageIfRequired(Frame)} (called by subclasses in order to pass down the frame or not). Starting
 * to send the frame invalidates the assumption. In other words, the assumption guards the fact that {@link #sendsFrame}
 * is a compilation constant, and is invalidated whenever it needs to change. */
@SuppressFBWarnings("IS")
public abstract class FrameAndVariablesSendingNode extends RubyContextNode {

    @Child protected FrameOrVariablesReadingNode sendingNode;

    public boolean sendingFrames() {
        if (sendingNode == null) {
            return false;
        } else {
            return sendingNode.sendingFrame();
        }
    }

    private void startSending(Reads variables, Reads frame) {
        if (sendingNode != null) {
            sendingNode.startSending(variables, frame);
        } else if (variables == Reads.SELF && frame == Reads.NOTHING) {
            sendingNode = insert(GetSpecialVariableStorage.create());
        } else if (variables == Reads.NOTHING && frame == Reads.SELF) {
            sendingNode = insert(new ReadOwnFrameNode());
        } else if (variables == Reads.CALLER && frame == Reads.NOTHING) {
            sendingNode = insert(new ReadCallerFrameNode());
        } else if (variables == Reads.NOTHING && frame == Reads.CALLER) {
            sendingNode = insert(new ReadCallerVariablesNode());
        }
    }

    /** Whether we are sending down the frame (because the called method reads it). */
    public void startSendingOwnFrame() {
        RubyRootNode root = (RubyRootNode) getRootNode();
        if (getContext().getCoreLibrary().isSend(root.getSharedMethodInfo())) {
            startSending(Reads.NOTHING, Reads.CALLER);
        } else {
            startSending(Reads.NOTHING, Reads.SELF);
        }
    }

    public void startSendingOwnVariables() {
        RubyRootNode root = (RubyRootNode) getRootNode();
        if (getContext().getCoreLibrary().isSend(root.getSharedMethodInfo())) {
            startSending(Reads.CALLER, Reads.NOTHING);
        } else {
            startSending(Reads.SELF, Reads.NOTHING);
        }
    }

    public Object getFrameOrStorageIfRequired(VirtualFrame frame) {
        if (sendingNode == null) {
            return null;
        } else {
            return sendingNode.execute(frame);
        }
    }

}
