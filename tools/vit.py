import numpy as np
def viterbi(x, tab, acc0=64, block=4096):
    """Optimal 4-bit delta codes for target x (0..127). Returns codes, decoded."""
    tab=np.asarray(tab); S=np.arange(128)
    nxt=S[:,None]+tab[None,:]                      # [state, code]
    valid=(nxt>=0)&(nxt<=127); nxtc=np.clip(nxt,0,127)
    codes=np.empty(len(x),np.uint8); acc=acc0
    for b0 in range(0,len(x),block):
        xb=x[b0:b0+block]; n=len(xb)
        cost=np.full(128,np.inf); cost[acc]=0
        bp_state=np.empty((n,128),np.int16); bp_code=np.empty((n,128),np.uint8)
        for i in range(n):
            cand=np.where(valid,cost[:,None],np.inf)   # [state,code]
            new=np.full(128,np.inf); 
            flat=cand.ravel(); tgt=nxtc.ravel()
            order=np.lexsort((flat,tgt))                # per target, min first
            first=np.r_[True,tgt[order][1:]!=tgt[order][:-1]]
            sel=order[first]; t=tgt[sel]
            new[t]=flat[sel]; bp_state[i,t]=sel//16; bp_code[i,t]=sel%16
            new+= (S-xb[i])**2
            cost=new
        s=int(np.argmin(cost))
        for i in range(n-1,-1,-1):
            codes[b0+i]=bp_code[i,s]; s=bp_state[i,s]
        # recompute end state
        acc=int(acc+tab[codes[b0:b0+n]].sum())
    dec=np.cumsum(tab[codes])+acc0
    return codes,dec
