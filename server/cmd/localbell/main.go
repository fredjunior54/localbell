// Package main is the entry point for the LocalBell server.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/fredjunior54/localbell/internal/cert"
	"github.com/fredjunior54/localbell/internal/config"
	"github.com/fredjunior54/localbell/internal/handler"
	"github.com/fredjunior54/localbell/internal/mdns"
)

// Version is set at build time via -ldflags "-X main.Version=x.y.z".
var Version = "dev"

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--version" {
		fmt.Println(Version)
		return
	}

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	log.Printf("Config loaded from %s", config.Path())

	// Load or generate TLS certificate
	certificate, err := cert.LoadOrCreate(cert.ConfigDir())
	if err != nil {
		log.Fatalf("Failed to load/create certificate: %v", err)
	}

	log.Printf("Certificate fingerprint: %s", certificate.Fingerprint)

	// Start mDNS server
	mdnsServer, err := mdns.Start(cfg.Port)
	if err != nil {
		log.Printf("Warning: mDNS failed to start: %v", err)
		log.Printf("The server will still work, but you'll need to use IP address instead of localbell.local")
	}

	savePIN := func(hash string) error {
		cfg.PINHash = hash
		return config.Save(&cfg)
	}
	getPINHash := func() string {
		return cfg.PINHash
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", handler.HomeHandler(cfg.Secret, certificate.Fingerprint, Version, getPINHash, savePIN))
	mux.HandleFunc("/favicon.png", handler.FaviconHandler())
	mux.HandleFunc("/health", handler.Health)
	mux.HandleFunc("/notification", handler.AuthMiddleware(cfg.Secret, handler.Notification))

	server := &http.Server{
		Addr:              cfg.Port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       time.Duration(cfg.ReadTimeout) * time.Second,
		WriteTimeout:      time.Duration(cfg.WriteTimeout) * time.Second,
		IdleTimeout:       time.Duration(cfg.IdleTimeout) * time.Second,
		TLSConfig: &tls.Config{
			Certificates: []tls.Certificate{certificate.TLSCert},
			MinVersion:   tls.VersionTLS12,
		},
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		log.Printf("LocalBell server started on port %s", cfg.Port)
		log.Printf("Open https://localhost%s in your browser to see the QR code", cfg.Port)
		if err := server.ListenAndServeTLS("", ""); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("Server error: %v", err)
		}
	}()

	<-ctx.Done()
	stop()

	log.Println("Shutting down...")
	if mdnsServer != nil {
		if err := mdnsServer.Stop(); err != nil {
			log.Printf("mDNS stop error: %v", err)
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("Server shutdown error: %v", err)
	}
}
