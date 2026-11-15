"""
Atajo para las consultas de solo lectura de pip.

pip carga toda su interfaz (rich, logging, traceback) en cada ejecucion y
tarda unos 700 ms en arrancar aunque solo quiera listar paquetes. Para
consultas de solo lectura no hace falta: se leen directamente los
.dist-info del directorio de paquetes.

La salida es identica a la de pip (mismos campos, mismo formato y misma
exclusion de paquetes). Cualquier orden con opciones, o que no sea de
lectura, devuelve 2 y la ejecuta el pip de verdad.

Uso:  python3 ocpip.py <orden de pip> [argumentos]
"""
import os
import sys

# Paquetes que pip no lista en "freeze" (los propios de la herramienta y
# los de desarrollo). Es la misma lista que usa pip/_internal/commands/
# freeze.py: stdlib_pkgs mas los de desarrollo.
_FREEZE_SKIP = {"pip", "setuptools", "distribute", "wheel", "pkg-resources"}


def _site_packages():
    """Directorio donde se instalan los paquetes."""
    for p in sys.path:
        if p and p.endswith("site-packages") and os.path.isdir(p):
            return p
    try:
        import pip
        d = os.path.dirname(os.path.dirname(os.path.abspath(pip.__file__)))
        if os.path.isdir(d):
            return d
    except Exception:
        pass
    return ""


def _meta_fields(path):
    """Campos del METADATA (o PKG-INFO) que usa pip al mostrar un paquete.

    Se guardan tambien las lineas repetidas (Requires-Dist, Project-URL),
    que pip usa para poner Requires y Home-page.
    """
    out = {}
    multi = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                if not line.strip():
                    break
                if ":" not in line:
                    continue
                k, v = line.split(":", 1)
                k = k.strip().lower()
                v = v.strip()
                if k not in out:
                    out[k] = v
                multi.setdefault(k, []).append(v)
    except Exception:
        pass
    out["__multi__"] = multi
    return out


def _norm(path):
    """En Android /data/user/0 es el mismo sitio que /data/data. pip
    imprime la segunda forma; se normaliza para que la salida sea igual."""
    return path.replace("/data/user/0/", "/data/data/")


def _dists():
    """(nombre, version, carpeta .dist-info) de lo instalado."""
    found = []
    root = _site_packages()
    if not root:
        return found
    try:
        entries = sorted(os.listdir(root))
    except Exception:
        return found
    for e in entries:
        if not e.endswith(".dist-info"):
            continue
        d = os.path.join(root, e)
        meta = _meta_fields(os.path.join(d, "METADATA"))
        name = meta.get("name") or ""
        ver = meta.get("version") or ""
        if not name:
            continue
        found.append((name, ver, d, meta))
    best = {}
    for name, ver, d, meta in found:
        cur = best.get(name)
        if cur is None or _ver_key(ver) > _ver_key(cur[0]):
            best[name] = (ver, d, meta)
    return [(n, v, d, m) for n, (v, d, m) in best.items()]


def _ver_key(v):
    partes = []
    for p in str(v).split("."):
        try:
            partes.append(int(p))
        except ValueError:
            partes.append(0)
    return partes


def _location(root, name, dist_dir):
    """Carpeta donde esta instalado el paquete, como la muestra pip.

    pip pone el directorio que contiene el paquete (site-packages), no la
    carpeta .dist-info.
    """
    return os.path.dirname(dist_dir)


def cmd_version(args):
    if args:
        return 2
    import pip
    ver = ""
    for n, v, _d, _m in _dists():
        if n.lower() == "pip":
            ver = v
            break
    # pip imprime la version de python con dos cifras: 3.14
    py = ".".join(sys.version.split()[0].split(".")[:2])
    print("pip %s from %s (python %s)" % (ver, _norm(os.path.dirname(pip.__file__)), py))


def cmd_list(args):
    if args:
        # Con opciones (--format, --outdated, --exclude...) lo hace pip.
        return 2
    rows = [(n, v) for n, v, _d, _m in _dists()]
    rows.sort(key=lambda r: r[0].lower())
    # Mismo formato de columnas que pip: la cabecera y el ancho de la
    # primera columna.
    width = max([len("Package")] + [len(n) for n, _ in rows])
    print("Package".ljust(width), "Version")
    print("-" * width, "-" * len("Version"))
    for name, ver in rows:
        print(name.ljust(width), ver)


def cmd_freeze(args):
    if args:
        return 2
    rows = [(n, v) for n, v, _d, _m in _dists()
            if n.lower() not in _FREEZE_SKIP]
    rows.sort(key=lambda r: r[0].lower())
    for name, ver in rows:
        print("%s==%s" % (name, ver))


def cmd_show(args):
    if not args:
        return 1
    wanted = set(a.lower() for a in args)
    root = _site_packages()
    # Quien depende de cada paquete (pip lo deduce de los Requires-Dist).
    required_by = {}
    for n, _v, _d, m in _dists():
        for req in (m.get("__multi__", {}).get("requires-dist") or []):
            dep = _req_name(req)
            if dep:
                required_by.setdefault(dep.lower(), []).append(n)
    for name, ver, dist_dir, meta in _dists():
        if name.lower() not in wanted:
            continue
        print("Name: %s" % name)
        print("Version: %s" % ver)
        # Home-page sale de la entrada Project-URL con etiqueta Homepage.
        home = ""
        for pu in (meta.get("__multi__", {}).get("project-url") or []):
            if "," in pu:
                label, url = pu.split(",", 1)
                if label.strip().lower() == "homepage":
                    home = url.strip()
                    break
        print("Summary: %s" % meta.get("summary", ""))
        print("Home-page: %s" % home)
        print("Author: %s" % meta.get("author", ""))
        print("Author-email: %s" % meta.get("author-email", ""))
        print("License-Expression: %s" % meta.get("license-expression", ""))
        # Location es la carpeta donde esta el paquete, no el .dist-info.
        print("Location: %s" % _norm(_location(root, name, dist_dir)))
        # Requires: sin las dependencias marcadas como extra.
        reqs = []
        for req in (meta.get("__multi__", {}).get("requires-dist") or []):
            if "extra ==" in req:
                continue
            r = _req_name(req)
            if r:
                reqs.append(r)
        print("Requires: %s" % ", ".join(reqs))
        print("Required-by: %s" % ", ".join(sorted(required_by.get(name.lower(), []))))
    return 0


def _req_name(req):
    """Nombre del paquete de un requisito (sin marcadores ni versiones)."""
    r = req.split(";")[0].strip()
    for sep in ("(", "["):
        if sep in r:
            r = r.split(sep)[0].strip()
    for sep in ("<", ">", "=", "!", "~"):
        if sep in r:
            r = r.split(sep)[0].strip()
    return r


def main(argv):
    if not argv:
        return 2
    cmd = argv[0]
    rest = argv[1:]
    if cmd in ("--version", "-V"):
        return cmd_version(rest)
    if cmd == "list":
        return cmd_list(rest)
    if cmd == "freeze":
        return cmd_freeze(rest)
    if cmd == "show":
        return cmd_show(rest)
    return 2   # orden no cubierta: que la ejecute el pip de verdad


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
