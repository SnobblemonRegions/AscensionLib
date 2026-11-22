package com.cobbleascend.store;

/** Every kind of committed operation the store records. */
public enum Kind {
    ACQUIRE, MIGRATE, GRANT, SPEND, ASSEMBLE_CATALYST,
    OBSERVE_LEVEL, UPGRADE, REFORGE, REFINE, PROMOTE, INSTALL_UNIQUE, REPLACE_UNIQUE,
    AWARD_ATTUNEMENT, FUSE, USE_SIGIL, PERFECT, OVERREACH, MINT_VOUCHER, REDEEM_VOUCHER, RESPEC_RANKS, RESPEC_UNIQUE, EXCHANGE, HAUNT, UNBOND,
    /** Field Research (docs/FIELD-RESEARCH-DESIGN.md): research and bound items, Bench studies, serums. */
    RESEARCH, RESEARCH_STUDY, SERUM
}
