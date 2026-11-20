(function () {
    function initializeAdminSidebar() {
        var page = document.body;
        var toggle = document.querySelector('.admin-sidebar-toggle');
        var backdrop = document.querySelector('[data-admin-sidebar-close]');
        var links = Array.prototype.slice.call(document.querySelectorAll('[data-admin-route]'));
        var pathname = window.location.pathname.replace(/\/+$/, '') || '/';
        var adminIndex = pathname.indexOf('/admin');
        var route = adminIndex >= 0 ? pathname.substring(adminIndex) : pathname;
        var activeRoute = route === '/admin' ? 'dashboard' : null;
        var routes = [
            { path: '/admin/productos', key: 'products' },
            { path: '/admin/ofertas', key: 'offers' },
            { path: '/admin/pedidos', key: 'orders' },
            { path: '/admin/clientes', key: 'clients' },
            { path: '/admin/testimonios', key: 'testimonials' },
            { path: '/admin/reportes', key: 'reports' },
            { path: '/admin/analitica', key: 'analytics' }
        ];

        routes.some(function (item) {
            if (route === item.path || route.indexOf(item.path + '/') === 0) {
                activeRoute = item.key;
                return true;
            }
            return false;
        });

        if (route === '/admin/historial-ofertas') activeRoute = 'offer-history';

        links.forEach(function (link) {
            var active = link.getAttribute('data-admin-route') === activeRoute;
            link.classList.toggle('active', active);
            if (active) {
                link.setAttribute('aria-current', 'page');
            } else {
                link.removeAttribute('aria-current');
            }
        });

        function setOpen(open) {
            page.classList.toggle('admin-sidebar-open', open);
            if (toggle) {
                toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
                toggle.setAttribute('aria-label', open ? 'Cerrar menú de administración' : 'Abrir menú de administración');
                toggle.innerHTML = open
                    ? '<i class="fas fa-xmark" aria-hidden="true"></i>'
                    : '<i class="fas fa-bars" aria-hidden="true"></i>';
            }
        }

        if (toggle) {
            toggle.addEventListener('click', function () {
                setOpen(!page.classList.contains('admin-sidebar-open'));
            });
        }

        if (backdrop) backdrop.addEventListener('click', function () { setOpen(false); });
        links.forEach(function (link) { link.addEventListener('click', function () { setOpen(false); }); });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') setOpen(false);
        });
        window.addEventListener('resize', function () {
            if (window.innerWidth > 1024) setOpen(false);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeAdminSidebar);
    } else {
        initializeAdminSidebar();
    }
}());
