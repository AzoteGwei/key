/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.prover.engine;

/// Why an automatic proof search stopped.
///
/// [ProofSearchInformation#reason()] has always carried this as prose, but prose cannot be
/// branched on, and the default stop condition's message conflates two different endings:
/// "Maximal number of rule applications reached or timed out." leaves a caller unable to tell a
/// search that ran out of budget from one that ran out of ideas. Those call for opposite
/// responses -- raise the budget, or stop raising it and help the prover -- so the difference is
/// worth reporting as data.
///
/// None of these values says anything about whether the proof closed. A search that stopped
/// because nothing was left to try stops the same way whether it closed everything or nothing;
/// only the proof itself answers that.
public enum StopReason {
    /// The strategy had no rule left to offer for any open goal.
    ///
    /// The prover did everything it knows how to do. If goals remain open, more time will not
    /// help: they need a proof script, an interactive step, a solver, or a specification that is
    /// not there. This is also how a completed proof ends, since closing the last goal leaves
    /// nothing to apply.
    EXHAUSTED,

    /// The search was told to stop at the first goal it could not close, and did.
    NON_CLOSEABLE_GOAL,

    /// The limit on rule applications was reached.
    ///
    /// The search was still finding things to do. Raising the limit may well finish it.
    MAX_RULES,

    /// The strategy's own time limit elapsed while the search was still making progress.
    TIMEOUT,

    /// A stop condition other than the usual limits ended the search.
    STOP_CONDITION,

    /// The thread running the search was interrupted, which is how cancellation reaches it.
    INTERRUPTED,

    /// The search stopped because the proof is flagged as erroneous.
    PROOF_ERRONEOUS,

    /// An exception ended the search. See [ProofSearchInformation#getException()].
    ERROR
}
