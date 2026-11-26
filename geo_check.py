import numpy as np, itertools
def box_sides(mn,mx):
    (a,b,c),(x,y,z)=mn,mx
    return [
     [(a,y,z),(x,y,z),(x,b,z)],[(a,b,c),(x,b,c),(x,y,c)],
     [(a,y,z),(a,b,z),(a,b,c)],[(x,y,c),(x,b,c),(x,b,z)],
     [(x,b,c),(a,b,c),(a,b,z)],[(x,y,z),(a,y,z),(a,y,c)]]
def plane(p):
    p1,p2,p3=map(np.array,p); n=np.cross(p1-p2,p3-p2); n=n/np.linalg.norm(n); return n,n@p1
def clip(poly,n,d,eps=1e-4):
    out=[]
    for i,p in enumerate(poly):
        q=poly[(i+1)%len(poly)]; dp=n@p-d; dq=n@q-d
        if dp<=eps: out.append(p)
        if (dp<-eps and dq>eps) or (dp>eps and dq<-eps):
            out.append(p+(q-p)*(dp/(dp-dq)))
    return out
def build(sides):
    pl=[plane(s) for s in sides]; res=[]
    for i,(n,d) in enumerate(pl):
        ax=np.eye(3)[np.argmin(abs(n))]; u=np.cross(n,ax); u/=np.linalg.norm(u); v=np.cross(n,u); c=n*d; s=65536
        poly=[c-u*s-v*s,c+u*s-v*s,c+u*s+v*s,c-u*s+v*s]
        for j,(m,e) in enumerate(pl):
            if j!=i and poly: poly=clip(poly,m,e)
        res.append((n,poly))
    return res
def area_vec(poly):
    s=np.zeros(3)
    for i in range(len(poly)): s+=np.cross(poly[i],poly[(i+1)%len(poly)])
    return s/2
# box
r=build(box_sides((0,0,0),(64,128,32)))
vol=0
for n,poly in r:
    assert len(poly)==4,len(poly)
    av=area_vec(poly); assert av@n>0, "winding not CCW wrt outward normal"
    vol+=(poly[0]@n)*np.linalg.norm(av)/3
print("box faces ok, volume",round(vol,3),"expected",64*128*32)
# wedge: cut top-right edge of box with slanted plane
s=box_sides((0,0,0),(64,64,64)); s.append([(64,0,64),(64,64,64-0),(0,64,64)][::-1] if False else [(0,0,64),(0,64,64),(64,64,0)])
r=build(s); print("wedge faces",[len(p) for n,p in r])
vol=sum((p[0]@n)*np.linalg.norm(area_vec(p))/3 for n,p in r if len(p)>=3); print("wedge vol",round(vol,2),"expected",64**3/2)
