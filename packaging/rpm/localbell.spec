%global debug_package %{nil}
%global __os_install_post %{nil}

Name:           localbell
Version:        %{pkg_version}
Release:        1
Summary:        Android notification forwarder for Linux desktop

License:        AGPL-3.0-only
URL:            https://github.com/fredjunior54/localbell
Source0:        %{name}-%{version}.tar.gz

Requires:       libxkbcommon, libglvnd-egl, libglvnd-glx, dbus
Recommends:     xdg-utils

%description
Displays Android notifications through the desktop D-Bus service
using a direct certificate-pinned TLS connection.

%prep
%autosetup

%install
install -Dm755 localbell %{buildroot}%{_bindir}/localbell
install -Dm644 localbell.desktop %{buildroot}%{_datadir}/applications/localbell.desktop
install -Dm644 localbell-icon.png %{buildroot}%{_datadir}/icons/hicolor/128x128/apps/localbell.png

%files
%license LICENSE CaskaydiaCove-LICENSE.txt Symbols-Nerd-LICENSE.txt
%doc NERD-FONTS.txt
%{_bindir}/localbell
%{_datadir}/applications/localbell.desktop
%{_datadir}/icons/hicolor/128x128/apps/localbell.png

%changelog
* Wed Dec 10 2025 Alessandro Lattao <alessandro@lattao.com>
- Initial package
